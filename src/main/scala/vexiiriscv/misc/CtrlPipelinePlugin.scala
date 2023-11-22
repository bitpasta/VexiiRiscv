package vexiiriscv.misc

import spinal.core.Area
import spinal.lib.misc.pipeline
import spinal.lib.misc.pipeline.Link
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable


trait PipelineService{
  def getConnectors() : Seq[Link]
}

class CtrlPipelinePlugin extends FiberPlugin with PipelineService{
  override def getConnectors(): Seq[Link] = logic.connectors
  val idToCtrl = mutable.LinkedHashMap[Int, pipeline.CtrlLink]()
  def ctrl(id : Int) = idToCtrl.getOrElseUpdate(id, pipeline.CtrlLink())
  def up = ctrl(0).up
  val logic = during build new Area{
    val idMax = idToCtrl.keys.max
    for(i <- 0 to idMax) ctrl(i).unsetName() //To ensure the creation to all intermediate nodes
    val ctrls = idToCtrl.toList.sortBy(_._1).map(_._2)
    val sc = for((from, to) <- (ctrls, ctrls.tail).zipped) yield new pipeline.StageLink(from.down, to.up) //.withoutCollapse()
    val connectors = (sc ++ ctrls).toSeq
  }
}