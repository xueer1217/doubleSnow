package org.seekloud.theia.roomManager.models.dao

import java.util

import org.seekloud.theia.protocol.ptcl.CommonInfo.{RecordInfo, UserInfo}
import org.seekloud.theia.protocol.ptcl.client2Manager.http.CommonProtocol.GetRecordListRsp
//import org.seekloud.theia.protocol.ptcl.processer2Manager.ProcessorProtocol.RecordData
import org.seekloud.theia.protocol.ptcl.distributor2Manager.DistributorProtocol.RecordData
import org.seekloud.theia.roomManager.utils.DBUtil._
import org.seekloud.theia.roomManager.models.SlickTables._
import slick.jdbc.PostgresProfile.api._
import org.seekloud.theia.roomManager.Boot.executor

import scala.collection.mutable
import scala.concurrent.Future


//已经废弃
object RecordDao {

//  def addRecord(roomId:Long, recordName:String, recordDes:String, startTime:Long, coverImg:String, viewNum:Int, likeNum:Int,duration:String) = {
//    db.run(tRecord += rRecord(1, roomId, startTime, coverImg, recordName, recordDes, viewNum, likeNum,duration))
//  }
//


//  def searchRecord(roomId:Long, startTime:Long):Future[Option[RecordInfo]] = {
//    val record = db.run(tRecord.filter(i => i.roomid === roomId && i.startTime === startTime).result.headOption)
//    record.flatMap{resOpt =>
//      if (resOpt.isEmpty)
//        Future(None)
//      else{
//        val r = resOpt.get
//        val res = UserInfoDao.searchByRoomId(r.roomid).map{w =>
//          if(w.nonEmpty){
//            Some(RecordInfo(r.id,r.roomid,r.recordName,r.recordDes,w.get.uid,w.get.userName,r.startTime,
//              UserInfoDao.getHeadImg(w.get.headImg),UserInfoDao.getCoverImg(r.coverImg),r.viewNum,r.likeNum,r.duration))
//          }else{
//            log.debug("获取主播信息失败，主播不存在")
//            Some(RecordInfo(r.id,r.roomid,r.recordName,r.recordDes,-1l,"",r.startTime,
//              UserInfoDao.getHeadImg(""),UserInfoDao.getCoverImg(r.coverImg),r.viewNum,r.likeNum,r.duration))
//          }
//        }
//        res
//      }
//    }
//  }


//  def deleteRecord(recordId:Long) = {
//    db.run(tRecord.filter(_.id === recordId).delete)
//  }

//  def searchRecordById(recordId:Long) ={
//    db.run(tRecord.filter(_.id === recordId).result.headOption)
//  }


//  def searchRecordById(recordIdList:List[Long]) ={
//    Future.sequence(recordIdList.map{id =>
//      db.run(tRecord.filter(_.id === id).result.headOption)
//    }).map{r => r.filter(_.nonEmpty).map(_.get).map(r => RecordData(r.roomid,r.startTime))}
//  }
//
//  def deleteRecordById(recordIdList:List[Long]) ={
//    val query = tRecord.filter{r =>
//      recordIdList.map{r.id === _}.reduceLeft(_ || _)
//    }
//    db.run(query.delete)
//  }
//
//

//
//  def getTotalNum = {
//    db.run(tRecord.length.result)
//  }

//  def updateViewNum(roomId:Long, startTime:Long, num:Int) = {
//    db.run(tRecord.filter(i => i.roomid === roomId && i.startTime === startTime).map(_.viewNum).update(num))
//  }

//  def getAuthorRecordList(roomId: Long): Future[List[RecordInfo]] = {
//    val resList = UserInfoDao.searchByRoomId(roomId).flatMap{
//      case Some(author) =>
//        val records = db.run(tRecord.filter(_.roomid === roomId).sortBy(_.startTime.reverse).result)
//        records.map{ls =>
//          val res = ls.map{r =>
//            RecordInfo(r.id,r.roomid,r.recordName,r.recordDes,author.uid,author.userName,r.startTime,
//              UserInfoDao.getHeadImg(author.headImg),UserInfoDao.getCoverImg(r.coverImg),r.viewNum,r.likeNum,r.duration)
//          }.toList
//          res
//        }
//      case None =>
//        log.debug("获取主播信息失败，主播不存在")
//        Future{List.empty[RecordInfo]}
//    }
//
//    resList
//  }
//
//  def getAuthorRecordTotalNum(roomId: Long): Future[Int] = {
//    db.run(tRecord.filter(_.roomid === roomId).length.result)
//  }

//  def deleteAuthorRecord(recordId: Long) = {
//    db.run(tRecord.filter(_.id === recordId).delete)
//  }

//  def addRecordAddr(recordId: Long, recordAddr: String): Future[Int] = {
//    db.run(tRecord.filter(_.id === recordId).map(_.recordAddr).update(recordAddr))
//  }


  def main(args: Array[String]): Unit = {
//    def update() = {
//      db.run(tRecord.filter(_.roomid =!= 5l).result.headOption).flatMap{valueOpt =>
//        if(valueOpt.nonEmpty){
//          db.run(tRecord.filter(_.roomid === 5l).map(_.likeNum).update(valueOpt.get.likeNum + 1))
//        }else{
//          Future(-1)
//        }
//      }
//    }
//    val a = List(2l,3l,4l).map{roomid =>
//      db.run(tRecord.filter(_.roomid === roomid).result)
//    }
//    val b = Future.sequence(a).map(_.flatten)

//    db.run(tRecord.forceInsert(rRecord(-1l,4l,4554l)))//强制插入不过滤自增项
//    db.run(tRecord ++= List(rRecord(-1l,4l,4554l)))//批量插入，过滤自增项
//    db.run(tRecord.forceInsertAll(Seq(rRecord(-1l,4l,4554l),rRecord(5l,65l,6356l))))
//    val a = List(4)
//    val b = mutable.LinkedList(3)
//    val c = a ++: b
//    println(c)
  }
}
