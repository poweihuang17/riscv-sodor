//**************************************************************************
// RISCV Processor 6-Stage Datapath
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Jan 13

package Sodor
{

import Chisel._
import Node._

import Constants._
import Common._

class DatToCtlIo extends Bundle() 
{
   val dec_inst    = Bits(OUTPUT, 32)
   val exe_br_eq   = Bool(OUTPUT)
   val exe_br_lt   = Bool(OUTPUT)
   val exe_br_ltu  = Bool(OUTPUT)
   val exe_br_type = UInt(OUTPUT,  4)

   val if_pred_taken      = Bool(OUTPUT)
   val exe_wrong_target   = Bool(OUTPUT) 
   val mem_ctrl_dmem_val  = Bool(OUTPUT)
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
   
   // Instruction Fetch State
   val if_reg_pc             = Reg(init=UInt(START_ADDR, conf.xprlen))
   
   // Instruction Decode State
   val dec_reg_inst          = Reg(init=BUBBLE)
   val dec_reg_pc            = Reg(init=UInt(0, conf.xprlen))
   val dec_reg_pred_target   = Reg(init=UInt(0, conf.xprlen))
   val dec_reg_pred_taken    = Reg(init=Bool(false))

   // Operand Select State
   val opsel_reg_inst          = Reg(init=BUBBLE)
   val opsel_reg_pc            = Reg(init=UInt(0, conf.xprlen))
   val opsel_reg_wbaddr        = Reg(UInt())
   val opsel_reg_rs1_addr      = Reg(UInt())
   val opsel_reg_rs2_addr      = Reg(UInt())
   val opsel_reg_rs1_data      = Reg(Bits())
   val opsel_reg_rs2_data      = Reg(Bits())
   val opsel_reg_imm_itype     = Reg(Bits())
   val opsel_reg_imm_jtype     = Reg(Bits())
   val opsel_reg_imm_btype     = Reg(Bits())
   val opsel_reg_imm_stype     = Reg(Bits())
   val opsel_reg_imm_utype     = Reg(Bits())
   val opsel_reg_zimm          = Reg(Bits())
   val opsel_reg_pcu           = Reg(Bits())
   val opsel_reg_ctrl_op1_sel  = Reg(UInt())
   val opsel_reg_ctrl_alu_fun  = Reg(UInt())
   val opsel_reg_ctrl_wb_sel   = Reg(UInt())
   val opsel_reg_pred_target   = Reg(UInt())
   val opsel_reg_pred_taken    = Reg(init=Bool(false))
  
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
   val if_pc_plus4         = UInt()
   val exe_brjmp_target    = UInt()
   val exe_jump_reg_target = UInt()
   val exe_pc_next         = UInt()

   when (!io.ctl.dec_stall && !io.ctl.full_stall) 
   {
      if_reg_pc       := if_pc_next
   }

   if_pc_plus4 := (if_reg_pc + UInt(4, conf.xprlen))               
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
  
   // Instruction Memory
   io.imem.req.bits.addr := if_reg_pc
   val if_inst = io.imem.resp.bits.data
   
   when (!io.ctl.dec_stall && !io.ctl.full_stall)
   {
      when (io.ctl.if_kill)
      {
         dec_reg_inst         := BUBBLE
         dec_reg_pred_taken   := Bool(false)
         dec_reg_pred_target  := UInt(0, conf.xprlen)
      }
      .otherwise
      {
         dec_reg_inst := if_inst
         dec_reg_pred_taken   := if_pred_taken
         dec_reg_pred_target  := if_pred_target
      }

      dec_reg_pc        := if_reg_pc
   }


   
   //**********************************
   // Decode Stage
   val dec_rs1_addr = dec_reg_inst(19, 15).toUInt
   val dec_rs2_addr = dec_reg_inst(24, 20).toUInt
   val dec_wbaddr  = Mux(io.ctl.wa_sel, dec_reg_inst(11, 7).toUInt, RA)
    
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

   val zimm = Cat(Fill(UInt(0), 27), dec_reg_inst(19,15))
   val pcu  = Cat(dec_reg_pc(31,12), Fill(UInt(0), 12))

   // sign-extend immediates
   val imm_itype_sext  = Cat(Fill(imm_itype(11), 20), imm_itype)
   val imm_stype_sext  = Cat(Fill(imm_stype(11), 20), imm_stype)
   val imm_sbtype_sext = Cat(Fill(imm_sbtype(11), 19), imm_sbtype, UInt(0,1))
   val imm_utype_sext  = Cat(imm_utype, Fill(UInt(0,1), 12))
   val imm_ujtype_sext = Cat(Fill(imm_ujtype(19), 11), imm_ujtype, UInt(0,1))


   when(!io.ctl.dec_stall && !io.ctl.full_stall)
   {
     opsel_reg_imm_itype     := imm_itype_sext
     opsel_reg_imm_stype     := imm_stype_sext
     opsel_reg_imm_btype     := imm_sbtype_sext
     opsel_reg_imm_jtype     := imm_ujtype_sext
     opsel_reg_imm_utype     := imm_utype_sext
     opsel_reg_zimm          := zimm
     opsel_reg_pcu           := pcu
 
     opsel_reg_pc            := dec_reg_pc
     opsel_reg_inst          := dec_reg_inst
     opsel_reg_rs1_addr      := dec_rs1_addr
     opsel_reg_rs2_addr      := dec_rs2_addr
     opsel_reg_rs2_data      := rf_rs2_data
     opsel_reg_rs1_data      := rf_rs1_data
     opsel_reg_wbaddr        := dec_wbaddr
     opsel_reg_ctrl_op1_sel  := io.ctl.op1_sel  
     opsel_reg_ctrl_alu_fun  := io.ctl.alu_fun  
     opsel_reg_ctrl_wb_sel   := io.ctl.wb_sel 
     opsel_reg_pred_target   := dec_reg_pred_target
     opsel_reg_pred_taken    := dec_reg_pred_taken


     when(io.ctl.dec_kill)
     {
       opsel_reg_imm_itype := UInt(0)
       opsel_reg_imm_stype := UInt(0)
       opsel_reg_imm_btype := UInt(0)
       opsel_reg_imm_jtype := UInt(0)
       opsel_reg_imm_utype := UInt(0)
       opsel_reg_zimm      := UInt(0)
       opsel_reg_pcu       := UInt(0)
     }
   }
   

   //********************************
   // Operand Select Stage

   // Operand 2 Mux   
   val opsel_alu_op2 = MuxCase(UInt(0), Array(
               (io.ctl.op2_sel === OP2_RS2)    -> opsel_reg_rs2_data,
               (io.ctl.op2_sel === OP2_ITYPE)  -> opsel_reg_imm_itype,
               (io.ctl.op2_sel === OP2_STYPE)  -> opsel_reg_imm_stype,
               (io.ctl.op2_sel === OP2_SBTYPE) -> opsel_reg_imm_btype, 
               (io.ctl.op2_sel === OP2_UTYPE)  -> opsel_reg_imm_utype,
               (io.ctl.op2_sel === OP2_UJTYPE) -> opsel_reg_imm_jtype
               )).toUInt



   // Bypass Muxes
   val exe_alu_out  = UInt(width = conf.xprlen)
   val mem_wbdata   = Bits(width = conf.xprlen)

   val opsel_op1_data = Bits(width = conf.xprlen)
   val opsel_op2_data = Bits(width = conf.xprlen)
   val opsel_rs2_data = Bits(width = conf.xprlen)
   
   if (USE_FULL_BYPASSING)
   {
      // roll the OP1 mux into the bypass mux logic
      opsel_op1_data := MuxCase(opsel_reg_rs1_data, Array(
                           ((opsel_reg_ctrl_op1_sel === OP1_ZIMM)) -> opsel_reg_zimm,
                           ((opsel_reg_ctrl_op1_sel === OP1_PCU)) -> opsel_reg_pcu,
                           ((opsel_reg_ctrl_op1_sel === OP1_PC)) -> opsel_reg_pc,
                           ((exe_reg_wbaddr === opsel_reg_rs1_addr) && (opsel_reg_rs1_addr != UInt(0)) && exe_reg_ctrl_rf_wen) -> exe_alu_out,
                           ((mem_reg_wbaddr === opsel_reg_rs1_addr) && (opsel_reg_rs1_addr != UInt(0)) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === opsel_reg_rs1_addr) && (opsel_reg_rs1_addr != UInt(0)) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))
                               
      opsel_op2_data := MuxCase(opsel_alu_op2, Array(
                           ((exe_reg_wbaddr === opsel_reg_rs2_addr) && (opsel_reg_rs2_addr != UInt(0)) && exe_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> exe_alu_out,
                           ((mem_reg_wbaddr === opsel_reg_rs2_addr) && (opsel_reg_rs2_addr != UInt(0)) && mem_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> mem_wbdata,
                           ((wb_reg_wbaddr  === opsel_reg_rs2_addr) && (opsel_reg_rs2_addr != UInt(0)) &&  wb_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> wb_reg_wbdata
                           ))
   
      opsel_rs2_data := MuxCase(opsel_reg_rs2_data, Array(
                           ((exe_reg_wbaddr === opsel_reg_rs2_addr) && (opsel_reg_rs2_addr != UInt(0)) && exe_reg_ctrl_rf_wen) -> exe_alu_out,
                           ((mem_reg_wbaddr === opsel_reg_rs2_addr) && (opsel_reg_rs2_addr != UInt(0)) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === opsel_reg_rs2_addr) && (opsel_reg_rs2_addr != UInt(0)) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))
   }
   else
   {
      // Rely only on control interlocking to resolve hazards
      opsel_op1_data := MuxCase(opsel_reg_rs1_data, Array(
                          ((opsel_reg_ctrl_op1_sel === OP1_ZIMM)) -> zimm,
                          ((opsel_reg_ctrl_op1_sel === OP1_PCU))  -> pcu, 
                          ((opsel_reg_ctrl_op1_sel === OP1_PC))   -> opsel_reg_pc
                          ))
      opsel_rs2_data := rf_rs2_data
      opsel_op2_data := opsel_alu_op2
   }
   
   
   when(!io.ctl.dec_stall && !io.ctl.full_stall)
   {
      // no stalling...
      exe_reg_pc            := opsel_reg_pc
      exe_reg_rs1_addr      := opsel_reg_rs1_addr
      exe_reg_rs2_addr      := opsel_reg_rs2_addr
      exe_reg_op1_data      := opsel_op1_data
      exe_reg_op2_data      := opsel_op2_data
      exe_reg_rs2_data      := opsel_rs2_data
      exe_reg_ctrl_op2_sel  := io.ctl.op2_sel  
      exe_reg_ctrl_alu_fun  := io.ctl.alu_fun  
      exe_reg_ctrl_wb_sel   := io.ctl.wb_sel 
      exe_reg_pred_target   := opsel_reg_pred_target
      exe_reg_pred_taken    := opsel_reg_pred_taken

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
         exe_reg_inst          := opsel_reg_inst
         exe_reg_wbaddr        := opsel_reg_wbaddr
         exe_reg_ctrl_rf_wen   := io.ctl.rf_wen
         exe_reg_ctrl_mem_val  := io.ctl.mem_val  
         exe_reg_ctrl_mem_fcn  := io.ctl.mem_fcn 
         exe_reg_ctrl_mem_typ  := io.ctl.mem_typ
         exe_reg_ctrl_csr_cmd  := io.ctl.csr_cmd
         exe_reg_ctrl_br_type  := io.ctl.br_type
         exe_reg_pred_target   := opsel_reg_pred_target
         exe_reg_pred_taken    := opsel_reg_pred_taken
      }
   }
   .elsewhen (io.ctl.dec_stall && !io.ctl.full_stall)
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
                  (exe_reg_ctrl_alu_fun === ALU_COPY_2)-> exe_alu_op2
                  ))

   // Branch/Jump Target Calculation
   val brjmp_offset     = exe_reg_op2_data
   val exe_pc_plus4     = (exe_reg_pc + UInt(4))(conf.xprlen-1,0)
   val exe_wrong_target = exe_reg_pred_target != exe_pc_next   
   exe_brjmp_target     := exe_reg_pc + brjmp_offset
   exe_jump_reg_target  := exe_adder_out

   exe_pc_next := MuxCase(exe_pc_plus4, Array(
                          (io.ctl.exe_pc_sel === PC_PLUS4) -> exe_pc_plus4,
                          (io.ctl.exe_pc_sel === PC_BRJMP) -> exe_brjmp_target,
                          (io.ctl.exe_pc_sel === PC_JALR)  -> exe_jump_reg_target
                          ))

   when (!io.ctl.full_stall)
   {
      mem_reg_pc            := exe_reg_pc
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
      mem_reg_ctrl_csr_cmd  := exe_reg_ctrl_csr_cmd
      mem_reg_ctrl_wb_sel   := exe_reg_ctrl_wb_sel
   }

   
   //**********************************
   // Memory Stage
   
   // Control Status Registers
   val csr = Module(new CSRFile())
   csr.io.host <> io.host
   csr.io.rw.addr  := mem_reg_op2_data(11,0)
   csr.io.rw.wdata := mem_reg_op1_data(30, 0)
   csr.io.rw.cmd   := mem_reg_ctrl_csr_cmd
   val csr_out = csr.io.rw.rdata

   csr.io.exception := Bool(false)
 
   // WB Mux
   mem_wbdata := MuxCase(mem_reg_alu_out, Array(
                  (mem_reg_ctrl_wb_sel === WB_ALU) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_PC4) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_MEM) -> io.dmem.resp.bits.data, 
                  (mem_reg_ctrl_wb_sel === WB_CSR) -> csr_out
                  )).toSInt()


   //**********************************
   // Writeback Stage

   when (!io.ctl.full_stall)
   {
      wb_reg_wbaddr        := mem_reg_wbaddr
      wb_reg_wbdata        := mem_wbdata
      wb_reg_ctrl_rf_wen   := mem_reg_ctrl_rf_wen
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
 
   
   // Time Stamp Counter & Retired Instruction Counter 
   val tsc_reg = Reg(init=UInt(0, conf.xprlen))
   tsc_reg := tsc_reg + UInt(1)

   val irt_reg = Reg(init=UInt(0, conf.xprlen))
   when (!io.ctl.full_stall && !io.ctl.dec_stall) { irt_reg := irt_reg + UInt(1) }
        
                                     
   // Printout
 /*  printf("Cyc= %d (0x%x, 0x%x, 0x%x, 0x%x, 0x%x) [%s, %s, %s, %s, %s] %s %s ExeInst: %s\n"
      , tsc_reg(31,0)
      , if_reg_pc
      , dec_reg_pc
      , exe_reg_pc
      , Reg(next=exe_reg_pc)
      , Reg(next=Reg(next=exe_reg_pc))
      , Disassemble(if_inst, true)
      , Disassemble(dec_reg_inst, true)
      , Disassemble(exe_reg_inst, true)
      , Reg(next=Disassemble(exe_reg_inst, true))
      , Reg(next=Reg(next=Disassemble(exe_reg_inst, true)))
      , Mux(io.ctl.full_stall, Str("FREEZE"), 
        Mux(io.ctl.dec_stall, Str("STALL "), Str(" ")))
      , Mux(io.ctl.exe_pc_sel === UInt(1), Str("BR"),
        Mux(io.ctl.exe_pc_sel === UInt(2), Str("J "),
        Mux(io.ctl.exe_pc_sel === UInt(3), Str("JR"),
        Mux(io.ctl.exe_pc_sel === UInt(4), Str("EX"),
        Mux(io.ctl.exe_pc_sel === UInt(0), Str("  "), Str("??"))))))
      , Disassemble(exe_reg_inst)
      )
 */
}

 
}
