package org.seekloud.theia.capture.demo

import scala.concurrent.Future
import org.seekloud.theia.capture.sdk.MediaCapture.executor

object TestScala {
  def main(args: Array[String]): Unit = {
    val list = Future(List(1,2,3))

    val fps:String = "30.000030"
    println(fps.toFloat.toInt)
    System.exit(0)
  }
}
