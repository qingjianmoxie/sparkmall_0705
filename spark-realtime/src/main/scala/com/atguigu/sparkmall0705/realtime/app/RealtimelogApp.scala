package com.atguigu.sparkmall0705.realtime.app

import java.text.SimpleDateFormat
import java.util
import java.util.Date

import com.atguigu.sparkmall0705.common.util.{MyKafkaUtil, RedisUtil}
import com.atguigu.sparkmall0705.realtime.bean.RealtimeAdslog
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}
import redis.clients.jedis.Jedis

object RealtimelogApp {

  def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf().setAppName("realtime_ads").setMaster("local[*]")
        val sc = new SparkContext(sparkConf)
        val ssc = new StreamingContext(sc,Seconds(5))
        val inputDStream: InputDStream[ConsumerRecord[String, String]] = MyKafkaUtil.getKafkaStream("ads_log",ssc)
         val adsStringDStream: DStream[String] = inputDStream.map{record=>
           record.value()
         }
/*         adsStringDStream.foreachRDD{rdd=>
            println(rdd.collect().mkString("\n"))*/


/*           1.1 保存每个用户每天点击每个广告的次数
             每次rdd  -> 取出结果      redis[k,v]     key :     用户+日期+广告    value：点击次数
           rdd[String] ->rdd[readtimelog] */
    val realtimeLogDSream: DStream[RealtimeAdslog] = adsStringDStream.map(adsString => {

      val logArr: Array[String] = adsString.split(" ")
      val dateMillSec: String = logArr(0)
      val area: String = logArr(1)
      val city: String = logArr(2)
      val userId: String = logArr(3)
      val adsId: String = logArr(4)
      val date = new Date(dateMillSec.toLong)
      RealtimeAdslog(date, area, city, userId, adsId)
    })


    val jedisClient: Jedis = RedisUtil.getJedisClient
//    val filteredRealtimelog: DStream[RealtimeAdslog] = realtimeLogDSream.filter { realtimeLog =>
//      !jedisClient.sismember("user_blacklist", realtimeLog.userId)
//    }
    //过滤掉黑名单中的用户日志
    val filteredRealtimelog: DStream[RealtimeAdslog]= realtimeLogDSream.transform{rdd=>
      val blackList: util.Set[String] = jedisClient.smembers("user_blacklist")
      val blackListBC: Broadcast[util.Set[String]] = sc.broadcast(blackList)
      val filteredRealtimeLogRDD: RDD[RealtimeAdslog] = rdd.filter { realtimlog =>
        !blackListBC.value.contains(realtimlog.userId)
      }
      filteredRealtimeLogRDD
    }

    /////////////////////////////////////////////////////////////////
    ///////////////////////////需求8//////////////////////////////////
    //////////////////////////////////////////////////////////////////
    //处理 需求8
    // 把明细变为 [k,v]结构  地区+城市+广告+天+ =>count
    val areaCityAdsCountDStream: DStream[(String, Long)] = filteredRealtimelog.map { realtimeAdslog =>
      val key = realtimeAdslog.area + ":" + realtimeAdslog.city + ":" + realtimeAdslog.adsId + ":" + realtimeAdslog.getDateString()
      (key, 1L)
    }.reduceByKey(_ + _)

    //
    //
    // =>利用updateStatebykey 把历史数据进行汇总，得到最新的汇总数据，然后汇总数据保存到redis
    sc.setCheckpointDir("./checkpoint")
    val areaCityAdsTotalCountDstream: DStream[(String, Long)] = areaCityAdsCountDStream.updateStateByKey { (adsCountSeq: Seq[Long], totalCount: Option[Long]) =>
      val adsCountSum: Long = adsCountSeq.sum
      val newTotalCount: Long = totalCount.getOrElse(0L) + adsCountSum
      Some(newTotalCount)
    }
    // 把汇总结果写入redis
    areaCityAdsTotalCountDstream.foreachRDD{  rdd:RDD[(String,Long)]=>
         val areaCityAdsTotalCountArray: Array[ (String,Long)] = rdd.collect()

      for ( (key,count)<- areaCityAdsTotalCountArray ) {
        jedisClient.hset("area_city_ads_day_clickcount",key,count.toString)
      }
    }

    ///需求九//////////////////////////////////////////
    /////////////////////////

    AreaTop3AdsPerdayApp.calcTop3Ads(areaCityAdsTotalCountDstream)
    println("需求九完成")


    LastHourCountPerAds.calcLastHourCountPerAds(filteredRealtimelog)
    println("需求十完成")



    //按天+用户+广告 进行聚合  计算点击量
    //           ->rdd[(userid_adsid_date,1L)  ]-> reducebykey->rdd[(userid_adsid_date,count)]
    val userAdsCountPerDayDSream: DStream[(String, Long)] = filteredRealtimelog.map { realtimelog =>
      val key: String = realtimelog.userId + ":" + realtimelog.adsId + ":" + realtimelog.getDateString()
      (key, 1L)
    }.reduceByKey(_ + _)




    //需求七
    //向redis中存放用户点击广告的累计值
    userAdsCountPerDayDSream.foreachRDD {rdd=>


//      ///在分片中建立连接的优化
//      rdd.foreachPartition{ itrabc=>
//        val jedisClient: Jedis = RedisUtil.getJedisClient
//        for (abc <-  itrabc ) {
//          // jedisClient //处理abc
//        }
//      }
//      //driver中的连接无法传递到executor中
//      rdd.map{log=>
//        //业务上需要条数据都要进行redis查询
//        // jedisClient
//      }

       val userAdsCountPerDayArr: Array[(String,Long)] = rdd.collect()

        for ( (key,count)<- userAdsCountPerDayArr ) {
          val countString: String = jedisClient.hget("user_ads_count_perday",key)
          //达到阈值 进入黑名单
          if(countString!=null&&countString.toLong>=10000){
            val userId: String = key.split(":")(0)
              //黑名单 结构 set
            jedisClient.sadd("user_blacklist",userId)

          }
          jedisClient.hincrBy("user_ads_count_perday",key,count)


        }

    }

    ssc.start()
    ssc.awaitTermination()
  }

}
