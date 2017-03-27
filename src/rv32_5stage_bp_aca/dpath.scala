//**************************************************************************
// RISCV Processor 5-Stage Datapath
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Jan 13
//
// TODO refactor stall, kill, fencei, flush signals. They're more confusing than they need to be.

package Sodor
{

import Chisel._
import Node._

import Constants._
import Common._

class DatToCtlIo(implicit conf: SodorConfiguration) extends Bundle()
{
   val dec_inst    = Bits(OUTPUT, 32)
   val exe_br_eq   = Bool(OUTPUT)
   val exe_br_lt   = Bool(OUTPUT)
   val exe_br_ltu  = Bool(OUTPUT)
   val exe_br_type = UInt(OUTPUT,  4)

   val mem_ctrl_dmem_val = Bool(OUTPUT)
   val if_pred_taken      = Bool(OUTPUT)
   val exe_wrong_target   = Bool(OUTPUT) 

   val csr_eret = Bool(OUTPUT)
   val csr_xcpt = Bool(OUTPUT)
   val csr_interrupt = Bool(OUTPUT)
   val csr_interrupt_cause = UInt(OUTPUT, conf.xprlen)
}

class DpathIo(implicit conf: SodorConfiguration) extends Bundle()
{
   val host  = new HTIFIO()
   val imem = new MemPortIo(conf.xprlen)
   val dmem = new MemPortIo(conf.xprlen)
   val ctl  = new CtlToDatIo().flip()
   val dat  = new DatToCtlIo()
}

class DatPath(implicit conf: SodorConfiguration) extends Module
{
   val io = new DpathIo()

   //**********************************
   // Pipeline State Registers

   //Branch predictor
   val bp_reg_pred_target = Reg(init=UInt(0, conf.xprlen))
   val bp_reg_pred_taken = Reg(init=Bool(false))

   // Instruction Fetch State
   val if_reg_pc             = Reg(init=UInt(START_ADDR, conf.xprlen))

   // Instruction Decode State
   val dec_reg_inst          = Reg(init=BUBBLE)
   val dec_reg_pc            = Reg(init=UInt(0, conf.xprlen))
   val dec_reg_pred_target   = Reg(init=UInt(0, conf.xprlen))
   val dec_reg_pred_taken    = Reg(init=Bool(false))

   // Execute State
   val exe_reg_inst          = Reg(init=BUBBLE)
   val exe_reg_pc            = Reg(init=UInt(0, conf.xprlen))
   val exe_reg_wbaddr        = Reg(UInt())
   val exe_reg_rs1_addr      = Reg(UInt())
   val exe_reg_rs2_addr      = Reg(UInt())
   val exe_reg_op1_data      = Reg(Bits())
   val exe_reg_op2_data      = Reg(Bits())
   val exe_reg_rs2_data      = Reg(Bits())
   val exe_reg_ctrl_br_type  = Reg(init=BR_N)
   val exe_reg_ctrl_op2_sel  = Reg(UInt())
   val exe_reg_ctrl_alu_fun  = Reg(UInt())
   val exe_reg_ctrl_wb_sel   = Reg(UInt())
   val exe_reg_ctrl_rf_wen   = Reg(init=Bool(false))
   val exe_reg_ctrl_mem_val  = Reg(init=Bool(false))
   val exe_reg_ctrl_mem_fcn  = Reg(init=M_X)
   val exe_reg_ctrl_mem_typ  = Reg(init=MT_X)
   val exe_reg_ctrl_csr_cmd  = Reg(init=CSR.N)
   val exe_reg_pred_target   = Reg(UInt())
   val exe_reg_pred_taken    = Reg(init=Bool(false))

   // Memory State
   val mem_reg_pc            = Reg(UInt())
   val mem_reg_inst          = Reg(Bits())
   val mem_reg_alu_out       = Reg(Bits())
   val mem_reg_wbaddr        = Reg(UInt())
   val mem_reg_rs1_addr      = Reg(UInt())
   val mem_reg_rs2_addr      = Reg(UInt())
   val mem_reg_op1_data      = Reg(Bits())
   val mem_reg_op2_data      = Reg(Bits())
   val mem_reg_rs2_data      = Reg(Bits())
   val mem_reg_ctrl_rf_wen   = Reg(init=Bool(false))
   val mem_reg_ctrl_mem_val  = Reg(init=Bool(false))
   val mem_reg_ctrl_mem_fcn  = Reg(init=M_X)
   val mem_reg_ctrl_mem_typ  = Reg(init=MT_X)
   val mem_reg_ctrl_wb_sel   = Reg(UInt())
   val mem_reg_ctrl_csr_cmd  = Reg(init=CSR.N)

   // Writeback State
   val wb_reg_wbaddr         = Reg(UInt())
   val wb_reg_wbdata         = Reg(Bits(width = conf.xprlen))
   val wb_reg_ctrl_rf_wen    = Reg(init=Bool(false))


   //**********************************
   // Instruction Fetch Stage
   val if_pc_next          = UInt()
   val exe_brjmp_target    = UInt()
   val exe_jump_reg_target = UInt()
   val exception_target    = UInt()
   val exe_pc_next         = UInt()

   when ((!io.ctl.dec_stall && !io.ctl.full_stall) || io.ctl.pipeline_kill)
   {
      if_reg_pc := if_pc_next
   }

   val if_pc_plus4 = (if_reg_pc + UInt(4, conf.xprlen))          
   val btb = Module(new BTB())
   btb.io.if_pc_reg             := if_reg_pc
   btb.io.exe_reg_pc            := exe_reg_pc
   btb.io.exe_pc_next           := exe_pc_next
   btb.io.exe_reg_ctrl_br_type  := exe_reg_ctrl_br_type
   btb.io.exe_reg_pred_taken    := exe_reg_pred_taken
   btb.io.exe_pc_sel            := io.ctl.exe_pc_sel

   val if_pred_taken  = btb.io.if_pred_taken
   val if_pred_target = btb.io.if_pred_target


  if_pc_next := MuxCase(if_pc_plus4, Array(
     (if_pred_taken && !io.ctl.mispredict) -> if_pred_target,
     io.ctl.mispredict -> exe_pc_next))

/*
   if_pc_next := Mux(io.ctl.exe_pc_sel === PC_4,      if_pc_plus4,
                 Mux(io.ctl.exe_pc_sel === PC_BRJMP,  exe_brjmp_target,
                 Mux(io.ctl.exe_pc_sel === PC_JALR,   exe_jump_reg_target,
                 /*Mux(io.ctl.exe_pc_sel === PC_EXC*/ exception_target)))
*/


   // for a fencei, refetch the if_pc (assuming no stall, no branch, and no exception)

   when (io.ctl.fencei && io.ctl.exe_pc_sel === PC_4 && 
         !io.ctl.dec_stall && !io.ctl.full_stall && !io.ctl.pipeline_kill)
   {
      if_pc_next := if_reg_pc
   }

   // Instruction Memory
   io.imem.req.bits.addr := if_reg_pc
   val if_inst = io.imem.resp.bits.data

//   when (io.ctl.pipeline_kill)
   when (io.ctl.pipeline_kill && io.ctl.mispredict)
   {
      dec_reg_inst := BUBBLE
      dec_reg_pred_taken   := Bool(false)
      dec_reg_pred_target  := UInt(0, conf.xprlen)
   }
   .elsewhen (!io.ctl.dec_stall && !io.ctl.full_stall)
   {
      when (io.ctl.if_kill)
      {
         dec_reg_inst := BUBBLE
         dec_reg_pred_taken   := Bool(false)
         dec_reg_pred_target  := UInt(0, conf.xprlen)
      }
      .otherwise
      {
         dec_reg_inst := if_inst
         dec_reg_pred_taken   := if_pred_taken
         dec_reg_pred_target  := if_pred_target
      }

      dec_reg_pc := if_reg_pc
   }


   //**********************************
   // Decode Stage
   val dec_rs1_addr = dec_reg_inst(19, 15)
   val dec_rs2_addr = dec_reg_inst(24, 20)
   val dec_wbaddr   = dec_reg_inst(11, 7)


   // Register File
   val regfile = Module(new RegisterFile())
      regfile.io.rs1_addr := dec_rs1_addr
      regfile.io.rs2_addr := dec_rs2_addr
      val rf_rs1_data = regfile.io.rs1_data
      val rf_rs2_data = regfile.io.rs2_data
      regfile.io.waddr := wb_reg_wbaddr
      regfile.io.wdata := wb_reg_wbdata
      regfile.io.wen   := wb_reg_ctrl_rf_wen


   // immediates
   val imm_itype  = dec_reg_inst(31,20)
   val imm_stype  = Cat(dec_reg_inst(31,25), dec_reg_inst(11,7))
   val imm_sbtype = Cat(dec_reg_inst(31), dec_reg_inst(7), dec_reg_inst(30, 25), dec_reg_inst(11,8))
   val imm_utype  = dec_reg_inst(31, 12)
   val imm_ujtype = Cat(dec_reg_inst(31), dec_reg_inst(19,12), dec_reg_inst(20), dec_reg_inst(30,21))

   val imm_z = Cat(Fill(UInt(0), 27), dec_reg_inst(19,15))

   // sign-extend immediates
   val imm_itype_sext  = Cat(Fill(imm_itype(11), 20), imm_itype)
   val imm_stype_sext  = Cat(Fill(imm_stype(11), 20), imm_stype)
   val imm_sbtype_sext = Cat(Fill(imm_sbtype(11), 19), imm_sbtype, UInt(0,1))
   val imm_utype_sext  = Cat(imm_utype, Fill(UInt(0,1), 12))
   val imm_ujtype_sext = Cat(Fill(imm_ujtype(19), 11), imm_ujtype, UInt(0,1))

   // Operand 2 Mux
   val dec_alu_op2 = MuxCase(UInt(0), Array(
               (io.ctl.op2_sel === OP2_RS2)    -> rf_rs2_data,
               (io.ctl.op2_sel === OP2_ITYPE)  -> imm_itype_sext,
               (io.ctl.op2_sel === OP2_STYPE)  -> imm_stype_sext,
               (io.ctl.op2_sel === OP2_SBTYPE) -> imm_sbtype_sext,
               (io.ctl.op2_sel === OP2_UTYPE)  -> imm_utype_sext,
               (io.ctl.op2_sel === OP2_UJTYPE) -> imm_ujtype_sext
               )).toUInt



   // Bypass Muxes
   val exe_alu_out  = UInt(width = conf.xprlen)
   val mem_wbdata   = Bits(width = conf.xprlen)

   val dec_op1_data = Bits(width = conf.xprlen)
   val dec_op2_data = Bits(width = conf.xprlen)
   val dec_rs2_data = Bits(width = conf.xprlen)

   if (USE_FULL_BYPASSING)
   {
      // roll the OP1 mux into the bypass mux logic
      dec_op1_data := MuxCase(rf_rs1_data, Array(
                           ((io.ctl.op1_sel === OP1_IMZ)) -> imm_z,
                           ((io.ctl.op1_sel === OP1_PC)) -> dec_reg_pc,
                           ((exe_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr != UInt(0)) && exe_reg_ctrl_rf_wen) -> exe_alu_out,
                           ((mem_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr != UInt(0)) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs1_addr) && (dec_rs1_addr != UInt(0)) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))

      dec_op2_data := MuxCase(dec_alu_op2, Array(
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr != UInt(0)) && exe_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> exe_alu_out,
                           ((mem_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr != UInt(0)) && mem_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs2_addr) && (dec_rs2_addr != UInt(0)) &&  wb_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> wb_reg_wbdata
                           ))

      dec_rs2_data := MuxCase(rf_rs2_data, Array(
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr != UInt(0)) && exe_reg_ctrl_rf_wen) -> exe_alu_out,
                           ((mem_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr != UInt(0)) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs2_addr) && (dec_rs2_addr != UInt(0)) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))
   }
   else
   {
      // Rely only on control interlocking to resolve hazards
      dec_op1_data := MuxCase(rf_rs1_data, Array(
                          ((io.ctl.op1_sel === OP1_IMZ)) -> imm_z,
                          ((io.ctl.op1_sel === OP1_PC))  -> dec_reg_pc
                          ))
      dec_rs2_data := rf_rs2_data
      dec_op2_data := dec_alu_op2
   }


//   when ((io.ctl.dec_stall && !io.ctl.full_stall) || io.ctl.pipeline_kill )
   when ((io.ctl.dec_stall && !io.ctl.full_stall) || (io.ctl.pipeline_kill && io.ctl.mispredict) )
   {
      // (kill exe stage)
      // insert NOP (bubble) into Execute stage on front-end stall (e.g., hazard clearing)
      exe_reg_inst          := BUBBLE
      exe_reg_wbaddr        := UInt(0)
      exe_reg_ctrl_rf_wen   := Bool(false)
      exe_reg_ctrl_mem_val  := Bool(false)
      exe_reg_ctrl_mem_fcn  := M_X
      exe_reg_ctrl_csr_cmd  := CSR.N
      exe_reg_ctrl_br_type  := BR_N
      exe_reg_pred_target   := UInt(0)
      exe_reg_pred_taken    := Bool(false)
   }
   .elsewhen(!io.ctl.dec_stall && !io.ctl.full_stall)
   {
      // no stalling...
      exe_reg_pc            := dec_reg_pc
      exe_reg_rs1_addr      := dec_rs1_addr
      exe_reg_rs2_addr      := dec_rs2_addr
      exe_reg_op1_data      := dec_op1_data
      exe_reg_op2_data      := dec_op2_data
      exe_reg_rs2_data      := dec_rs2_data
      exe_reg_ctrl_op2_sel  := io.ctl.op2_sel
      exe_reg_ctrl_alu_fun  := io.ctl.alu_fun
      exe_reg_ctrl_wb_sel   := io.ctl.wb_sel
      exe_reg_pred_target   := dec_reg_pred_target
      exe_reg_pred_taken    := dec_reg_pred_taken

      when (io.ctl.dec_kill)
      {
         exe_reg_inst          := BUBBLE
         exe_reg_wbaddr        := UInt(0)
         exe_reg_ctrl_rf_wen   := Bool(false)
         exe_reg_ctrl_mem_val  := Bool(false)
         exe_reg_ctrl_mem_fcn  := M_X
         exe_reg_ctrl_csr_cmd  := CSR.N
         exe_reg_ctrl_br_type  := BR_N
         exe_reg_pred_target   := UInt(0)
         exe_reg_pred_taken    := Bool(false)
      }
      .otherwise
      {
         exe_reg_inst          := dec_reg_inst
         exe_reg_wbaddr        := dec_wbaddr
         exe_reg_ctrl_rf_wen   := io.ctl.rf_wen
         exe_reg_ctrl_mem_val  := io.ctl.mem_val
         exe_reg_ctrl_mem_fcn  := io.ctl.mem_fcn
         exe_reg_ctrl_mem_typ  := io.ctl.mem_typ
         exe_reg_ctrl_csr_cmd  := io.ctl.csr_cmd
         exe_reg_ctrl_br_type  := io.ctl.br_type
         exe_reg_pred_target   := dec_reg_pred_target
         exe_reg_pred_taken    := dec_reg_pred_taken
      }
   }

   //**********************************
   // Execute Stage

   val exe_alu_op1 = exe_reg_op1_data.toUInt
   val exe_alu_op2 = exe_reg_op2_data.toUInt

   // ALU
   val alu_shamt     = exe_alu_op2(4,0).toUInt
   val exe_adder_out = (exe_alu_op1 + exe_alu_op2)(conf.xprlen-1,0)

//   exe_alu_out := MuxCase(UInt(0), Array(
   //only for debug purposes right now until debug() works
   exe_alu_out := MuxCase(exe_reg_inst.toUInt, Array(
                  (exe_reg_ctrl_alu_fun === ALU_ADD)  -> exe_adder_out,
                  (exe_reg_ctrl_alu_fun === ALU_SUB)  -> (exe_alu_op1 - exe_alu_op2).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_AND)  -> (exe_alu_op1 & exe_alu_op2).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_OR)   -> (exe_alu_op1 | exe_alu_op2).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_XOR)  -> (exe_alu_op1 ^ exe_alu_op2).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLT)  -> (exe_alu_op1.toSInt < exe_alu_op2.toSInt).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLTU) -> (exe_alu_op1 < exe_alu_op2).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLL)  -> ((exe_alu_op1 << alu_shamt)(conf.xprlen-1, 0)).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SRA)  -> (exe_alu_op1.toSInt >> alu_shamt).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SRL)  -> (exe_alu_op1 >> alu_shamt).toUInt,
                  (exe_reg_ctrl_alu_fun === ALU_COPY_1)-> exe_alu_op1,
                  (exe_reg_ctrl_alu_fun === ALU_COPY_2)-> exe_alu_op2
                  ))

   // Branch/Jump Target Calculation
   val brjmp_offset    = exe_reg_op2_data
   val exe_pc_plus4     = (exe_reg_pc + UInt(4))(conf.xprlen-1,0)
   val exe_wrong_target = exe_reg_pred_target != exe_pc_next   
   exe_brjmp_target    := exe_reg_pc + brjmp_offset
   exe_jump_reg_target := exe_adder_out


   exe_pc_next := MuxCase(exe_pc_plus4, Array(
                          (io.ctl.exe_pc_sel === PC_4) -> exe_pc_plus4,
                          (io.ctl.exe_pc_sel === PC_BRJMP) -> exe_brjmp_target,
                          (io.ctl.exe_pc_sel === PC_JALR)  -> exe_jump_reg_target,
                          (io.ctl.exe_pc_sel === PC_EXC) -> exception_target
                          ))

   // when (io.ctl.pipeline_kill )
   when (io.ctl.pipeline_kill && io.ctl.mispredict)
   {
      mem_reg_pc            := BUBBLE
      mem_reg_ctrl_rf_wen   := Bool(false)
      mem_reg_ctrl_mem_val  := Bool(false)
      mem_reg_ctrl_csr_cmd  := Bool(false)
   }
   .elsewhen (!io.ctl.full_stall)
   {
      mem_reg_pc            := exe_reg_pc
      mem_reg_inst          := exe_reg_inst
      mem_reg_alu_out       := Mux((exe_reg_ctrl_wb_sel === WB_PC4), exe_pc_plus4, exe_alu_out)
      mem_reg_wbaddr        := exe_reg_wbaddr
      mem_reg_rs1_addr      := exe_reg_rs1_addr
      mem_reg_rs2_addr      := exe_reg_rs2_addr
      mem_reg_op1_data      := exe_reg_op1_data
      mem_reg_op2_data      := exe_reg_op2_data
      mem_reg_rs2_data      := exe_reg_rs2_data
      mem_reg_ctrl_rf_wen   := exe_reg_ctrl_rf_wen
      mem_reg_ctrl_mem_val  := exe_reg_ctrl_mem_val
      mem_reg_ctrl_mem_fcn  := exe_reg_ctrl_mem_fcn
      mem_reg_ctrl_mem_typ  := exe_reg_ctrl_mem_typ
      mem_reg_ctrl_wb_sel   := exe_reg_ctrl_wb_sel
      mem_reg_ctrl_csr_cmd  := exe_reg_ctrl_csr_cmd
   }

   //**********************************
   // Memory Stage

   // Control Status Registers
   // The CSRFile can redirect the PC so it's easiest to put this in Execute for now.
   val csr = Module(new CSRFile())
   csr.io.host <> io.host
   csr.io.rw.addr  := mem_reg_inst(CSR_ADDR_MSB,CSR_ADDR_LSB)
   csr.io.rw.wdata := mem_reg_alu_out
   csr.io.rw.cmd   := mem_reg_ctrl_csr_cmd

   csr.io.retire    := !io.ctl.full_stall && !io.ctl.dec_stall
   csr.io.exception := io.ctl.mem_exception
   csr.io.cause     := io.ctl.mem_exc_cause
   csr.io.pc        := mem_reg_pc
   exception_target := csr.io.evec

   io.dat.csr_eret := csr.io.eret
   io.dat.csr_xcpt := csr.io.csr_xcpt
   io.dat.csr_interrupt := csr.io.interrupt
   io.dat.csr_interrupt_cause := csr.io.interrupt_cause
   // TODO replay? stall?

   // Add your own uarch counters here!
   csr.io.uarch_counters.foreach(_ := Bool(false))


   // WB Mux
   mem_wbdata := MuxCase(mem_reg_alu_out, Array(
                  (mem_reg_ctrl_wb_sel === WB_ALU) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_PC4) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_MEM) -> io.dmem.resp.bits.data,
                  (mem_reg_ctrl_wb_sel === WB_CSR) -> csr.io.rw.rdata
                  )).toSInt()


   //**********************************
   // Writeback Stage

   when (!io.ctl.full_stall)
   {
      wb_reg_wbaddr        := mem_reg_wbaddr
      wb_reg_wbdata        := mem_wbdata
      wb_reg_ctrl_rf_wen   := Mux(io.dat.csr_xcpt, Bool(false), mem_reg_ctrl_rf_wen)
   }
   .otherwise
   {
      wb_reg_ctrl_rf_wen   := Bool(false)
   }



   //**********************************
   // External Signals

   // datapath to controlpath outputs
   io.dat.dec_inst   := dec_reg_inst
   io.dat.exe_br_eq  := (exe_reg_op1_data === exe_reg_rs2_data)
   io.dat.exe_br_lt  := (exe_reg_op1_data.toSInt < exe_reg_rs2_data.toSInt)
   io.dat.exe_br_ltu := (exe_reg_op1_data.toUInt < exe_reg_rs2_data.toUInt)
   io.dat.exe_br_type:= exe_reg_ctrl_br_type

   io.dat.mem_ctrl_dmem_val := mem_reg_ctrl_mem_val

   io.dat.exe_wrong_target  := exe_wrong_target    
   io.dat.if_pred_taken     := if_pred_taken

   // datapath to data memory outputs
   io.dmem.req.valid     := mem_reg_ctrl_mem_val
   io.dmem.req.bits.addr := mem_reg_alu_out.toUInt
   io.dmem.req.bits.fcn  := mem_reg_ctrl_mem_fcn
   io.dmem.req.bits.typ  := mem_reg_ctrl_mem_typ
   io.dmem.req.bits.data := mem_reg_rs2_data


printf("Cyc= %d [%s][%x %x][%s](%x, %x, %x, %x, %x) [%x, %x, %x, %x, %x] %s %s ExeInst: DASM(%x)\n"
      , csr.io.time(31,0)

      , Mux(if_pred_taken, Str("T"), Str(" "))      
      , if_pc_next
      , if_pred_target

      , Mux(io.ctl.mispredict, Str("M"),
         Mux(io.ctl.hitpredict, Str("H"), Str(" ")
            ))

      , if_reg_pc
      , dec_reg_pc
      , exe_reg_pc      
      , Reg(next=exe_reg_pc)
      , Reg(next=Reg(next=exe_reg_pc))
      
      // TODO come up with a way to print out the opcode name, instead of just the number
      , if_inst(6,0)
      , dec_reg_inst(6,0)
      , exe_reg_inst(6,0)
      , Reg(next=exe_reg_inst(6,0))
      , Reg(next=Reg(next=exe_reg_inst(6,0)))
   
      , Mux(io.ctl.full_stall, Str("FREEZE"),
        Mux(io.ctl.dec_stall, Str("STALL "), Str(" ")))
      , Mux(io.ctl.exe_pc_sel === UInt(1), Str("BJ"),
        Mux(io.ctl.exe_pc_sel === UInt(2), Str("JR"),
        Mux(io.ctl.exe_pc_sel === UInt(3), Str("EX"),
        Mux(io.ctl.exe_pc_sel === UInt(0), Str("  "), Str("??")))))
      , Mux(io.ctl.pipeline_kill, BUBBLE, exe_reg_inst)

      )

/*
   // Printout
   printf("Cyc= %d [%x %x %d] [%d]{%d %x %x %d} (0x%x, 0x%x, 0x%x, 0x%x, 0x%x) [%x, %x, %x, %x, %x] %s %s ExeInst: DASM(%x)\n"
      , csr.io.time(31,0)

      , btb.io.if_pred_target
      , if_pc_next
      , if_pred_taken

      , io.ctl.mispredict
      
      , exe_reg_pred_taken
      , exe_pc_next
      , exe_reg_pred_target
      , exe_reg_ctrl_br_type

      , if_reg_pc
      , dec_reg_pc
      , exe_reg_pc
      , Reg(next=exe_reg_pc)
      , Reg(next=Reg(next=exe_reg_pc))
      // TODO come up with a way to print out the opcode name, instead of just the number
      , if_inst(6,0)
      , dec_reg_inst(6,0)
      , exe_reg_inst(6,0)
      , Reg(next=exe_reg_inst(6,0))
      , Reg(next=Reg(next=exe_reg_inst(6,0)))
      , Mux(io.ctl.full_stall, Str("FREEZE"),
        Mux(io.ctl.dec_stall, Str("STALL "), Str(" ")))
      , Mux(io.ctl.exe_pc_sel === UInt(1), Str("BJ"),
        Mux(io.ctl.exe_pc_sel === UInt(2), Str("JR"),
        Mux(io.ctl.exe_pc_sel === UInt(3), Str("EX"),
        Mux(io.ctl.exe_pc_sel === UInt(0), Str("  "), Str("??")))))
      , Mux(io.ctl.pipeline_kill, BUBBLE, exe_reg_inst)
      )
*/

}


}
