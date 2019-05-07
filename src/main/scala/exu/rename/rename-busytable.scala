package boom.exu

import chisel3._
import chisel3.util._
import boom.common._
import boom.util._
import freechips.rocketchip.config.Parameters

class BusyResp extends Bundle
{
  val prs1_busy = Bool()
  val prs2_busy = Bool()
  val prs3_busy = Bool()
}

class RenameBusyTable(
  val plWidth: Int,
  val numPregs: Int,
  val numWbPorts: Int,
  val float: Boolean)
  (implicit p: Parameters) extends BoomModule
{
  val pregSz = log2Ceil(numPregs)

  val io = IO(new BoomBundle()(p) {
    val ren_uops = Input(Vec(plWidth, new MicroOp))
    val busy_reqs = Input(Vec(plWidth, new MapResp(pregSz)))
    val busy_resps = Output(Vec(plWidth, new BusyResp))

    val rebusy_reqs = Input(Vec(plWidth, Bool()))

    val wb_pdsts = Input(Vec(numWbPorts, UInt(pregSz.W)))
    val wb_valids = Input(Vec(numWbPorts, Bool()))

    val debug = new Bundle { val busytable = Output(Bits(numPregs.W)) }
  })

  val busy_table = RegInit(0.U(numPregs.W))
  // Unbusy written back registers.
  val busy_table_wb = busy_table & ~(io.wb_pdsts zip io.wb_valids)
    .map {case (pdst, valid) => UIntToOH(pdst) & Fill(numPregs, valid.asUInt)}.reduce(_|_)
  // Rebusy newly allocated registers.
  val busy_table_next = busy_table_wb | (io.ren_uops zip io.rebusy_reqs)
    .map {case (uop, req) => UIntToOH(uop.pdst) & Fill(numPregs, req.asUInt)}.reduce(_|_)

  busy_table := busy_table_next

  // Read the busy table.
  for (i <- 0 until plWidth) {
    val prs1_was_bypassed = (0 until i).map(j => io.ren_uops(i).lrs1 === io.ren_uops(j).ldst && io.rebusy_reqs(j))
      .foldLeft(false.B)(_||_)
    val prs2_was_bypassed = (0 until i).map(j => io.ren_uops(i).lrs2 === io.ren_uops(j).ldst && io.rebusy_reqs(j))
      .foldLeft(false.B)(_||_)
    val prs3_was_bypassed = (0 until i).map(j => io.ren_uops(i).lrs3 === io.ren_uops(j).ldst && io.rebusy_reqs(j))
      .foldLeft(false.B)(_||_)

    io.busy_resps(i).prs1_busy := busy_table(io.busy_reqs(i).prs1) || prs1_was_bypassed
    io.busy_resps(i).prs2_busy := busy_table(io.busy_reqs(i).prs2) || prs2_was_bypassed
    if (float) io.busy_resps(i).prs3_busy := busy_table(io.busy_reqs(i).prs3) || prs3_was_bypassed
    else io.busy_resps(i).prs3_busy := false.B
  }

  io.debug.busytable := busy_table
}
