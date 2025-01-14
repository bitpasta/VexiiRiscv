package vexiiriscv.execute

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.lib.misc.pipeline._
import vexiiriscv.misc.{PrivilegedPlugin, TrapReason, TrapService}
import vexiiriscv.riscv.{CSR, Rvi}
import vexiiriscv._
import vexiiriscv.Global._
import vexiiriscv.decode.Decode
import vexiiriscv.schedule.ReschedulePlugin

import scala.collection.mutable.ArrayBuffer


object EnvPluginOp extends SpinalEnum{
  val ECALL, EBREAK, PRIV_RET = newElement()
}

class EnvPlugin(layer : LaneLayer,
                executeAt : Int) extends ExecutionUnitElementSimple(layer){
  val OP = Payload(EnvPluginOp())

  val logic = during setup new Logic{
    val sp = host[ReschedulePlugin]
    val ts = host[TrapService]
    val ps = host[PrivilegedPlugin]
    val ioRetainer = retains(sp.elaborationLock, ts.trapLock)
    awaitBuild()

    val age = eu.getExecuteAge(executeAt)
    val trapPort = ts.newTrap(age, Execute.LANE_AGE_WIDTH)
    val flushPort = sp.newFlushPort(age, Execute.LANE_AGE_WIDTH, true)

    add(Rvi.ECALL).decode(OP -> EnvPluginOp.ECALL)
    add(Rvi.EBREAK).decode(OP -> EnvPluginOp.EBREAK)
    add(Rvi.MRET).decode(OP -> EnvPluginOp.PRIV_RET)
    if (ps.implementSupervisor) add(Rvi.SRET).decode(OP -> EnvPluginOp.PRIV_RET)
    if (ps.implementUserTrap)   add(Rvi.URET).decode(OP -> EnvPluginOp.PRIV_RET)

    val uopList = ArrayBuffer(Rvi.ECALL, Rvi.EBREAK, Rvi.MRET)
    if (ps.implementSupervisor) uopList += (Rvi.SRET)
    if (ps.implementUserTrap) uopList += (Rvi.URET)

    for (uop <- uopList; spec = layer(uop)) {
      spec.setCompletion(executeAt)
      spec.mayFlushUpTo(executeAt)
    }

    uopRetainer.release()
    ioRetainer.release()

    val exe = new eu.Execute(executeAt){
      flushPort.valid := False
      flushPort.hartId := Global.HART_ID
      flushPort.uopId := Decode.UOP_ID
      flushPort.laneAge := Execute.LANE_AGE
      flushPort.self := False

      trapPort.valid := False
      trapPort.exception := True
      trapPort.code.assignDontCare()
      trapPort.tval := B(PC).andMask(OP === EnvPluginOp.EBREAK) //That's what spike do

      val privilege = ps.getPrivilege(HART_ID)
      val xretPriv = Decode.UOP(29 downto 28).asUInt
      val commit = False

      switch(this(OP)) {
        is(EnvPluginOp.EBREAK) {
          trapPort.code := CSR.MCAUSE_ENUM.BREAKPOINT
        }
        is(EnvPluginOp.ECALL) {
          trapPort.code := B(privilege.resize(Global.CODE_WIDTH) | CSR.MCAUSE_ENUM.ECALL_USER)
        }
        is(EnvPluginOp.PRIV_RET) {
          when(xretPriv >= ps.getPrivilege(HART_ID)) {
            commit := True
            trapPort.exception := False
            trapPort.code := TrapReason.PRIV_RET
            trapPort.tval(1 downto 0) := xretPriv.asBits
          } otherwise {
            trapPort.code := CSR.MCAUSE_ENUM.ILLEGAL_INSTRUCTION
          }
        }
      }

      when(isValid && SEL) {
        flushPort.valid := True
        trapPort.valid := True
        bypass(Global.TRAP) := True
        when(!commit) {
          bypass(COMMIT) := False
        }
      }
    }
  }
}
