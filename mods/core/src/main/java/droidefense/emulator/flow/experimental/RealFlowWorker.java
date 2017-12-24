package droidefense.emulator.flow.experimental;

import droidefense.emulator.flow.base.AbstractFlowWorker;
import droidefense.emulator.machine.base.AbstractDVMThread;
import droidefense.emulator.machine.base.DalvikVM;
import droidefense.emulator.machine.base.exceptions.NoMainClassFoundException;
import droidefense.emulator.machine.base.struct.generic.IDroidefenseClass;
import droidefense.emulator.machine.base.struct.generic.IDroidefenseFrame;
import droidefense.emulator.machine.base.struct.generic.IDroidefenseMethod;
import droidefense.emulator.machine.inst.InstructionReturn;
import droidefense.emulator.machine.reader.DexClassReader;
import droidefense.log4j.Log;
import droidefense.log4j.LoggerType;
import droidefense.rulengine.map.BasicCFGFlowMap;
import droidefense.rulengine.nodes.EntryPointNode;
import droidefense.emulator.machine.inst.DalvikInstruction;
import droidefense.sdk.model.base.DroidefenseProject;
import droidefense.sdk.util.ExecutionTimer;

import java.util.ArrayList;
import java.util.Vector;

public final strictfp class RealFlowWorker extends AbstractFlowWorker {

    public RealFlowWorker(DroidefenseProject project) {
        super(new DalvikVM(project), project);
        flowMap = new BasicCFGFlowMap();
    }

    public RealFlowWorker(final DalvikVM vm, DroidefenseProject project) {
        super(vm, project);
        flowMap = new BasicCFGFlowMap();
    }

    @Override
    public void preload() {
        Log.write(LoggerType.DEBUG, "WORKER: RealFlowWorker");
        this.setStatus(AbstractDVMThread.STATUS_NOT_STARTED);
        vm.setThreads(new Vector());
        vm.addThread(this);
    }

    @Override
    public void run() throws Throwable {
        //get main class and load
        if (currentProject.hasMainClass()) {
            DexClassReader.getInstance().load(currentProject.getMainClassName());
            execute(true);
        } else {
            throw new NoMainClassFoundException(currentProject.getProjectName() + " >> check main class manually");
        }
    }

    @Override
    public void finish() {
        Log.write(LoggerType.DEBUG, "WORKER: RealFlowWorker FINISHED!");
    }

    @Override
    public IDroidefenseMethod[] getInitialMethodToRun(IDroidefenseClass clazz) {
        ArrayList<IDroidefenseMethod> list = new ArrayList<>();
        /*IDroidefenseMethod[] l0 = clazz.getMethod("<init>");
        for (IDroidefenseMethod m : l0) {
            list.add(m);
        }*/
        list.add(clazz.getMethod("onCreate", "(Landroid/os/Bundle;)V", true));
        return list.toArray(new IDroidefenseMethod[list.size()]);
    }

    @Override
    public int getInitialArgumentCount(IDroidefenseClass cls, IDroidefenseMethod m) {
        return 0;
    }

    @Override
    public Object getInitialArguments(IDroidefenseClass cls, IDroidefenseMethod m) {
        return null;
    }

    @Override
    public IDroidefenseClass[] getInitialDVMClass() {
        //get all
        if (currentProject.hasMainClass())
            return new IDroidefenseClass[]{currentProject.getInternalInfo().getDexClass(currentProject.getMainClassName())};
        else {
            return currentProject.getDeveloperClasses();
        }
    }

    @Override
    public AbstractDVMThread cleanThreadContext() {
        //cleanThreadContext 'thread' status
        this.setStatus(AbstractDVMThread.STATUS_NOT_STARTED);
        this.removeFrames();
        this.timestamp = new ExecutionTimer();
        return this;
    }

    @Override
    public strictfp void execute(boolean endless) throws Throwable {

        IDroidefenseFrame frame = getCurrentFrame();
        IDroidefenseMethod method = frame.getMethod();

        int[] lowerCodes = method.getOpcodes();
        int[] upperCodes = method.getRegistercodes();
        int[] codes = method.getIndex();

        fromNode = EntryPointNode.builder();

        while (endless) {
            try {
                //1 ask if we have more inst to execute
                if (frame.getPc() >= lowerCodes.length || getFrames() == null || getFrames().isEmpty())
                    break;
                //skip sdk classes for faster execution
                if (method.isFake()) {
                    popFrame();
                    frame = getCurrentFrame();
                    if (frame != null) {
                        method = frame.getMethod();
                        if (method != null) {
                            upperCodes = method.getRegistercodes();
                            lowerCodes = method.getOpcodes();
                            codes = method.getIndex();
                            continue;
                        }
                    }
                    break;
                }
                int instVal = lowerCodes[frame.getPc()];
                Log.write(LoggerType.TRACE, "DalvikInstruction: 0x" + Integer.toHexString(instVal));
                DalvikInstruction currentInstruction = AbstractDVMThread.instructions[instVal];
                InstructionReturn returnValue = currentInstruction.execute(flowMap, this, lowerCodes, upperCodes, codes, DalvikInstruction.CFG_EXECUTION);
                if (returnValue != null) {
                    //first check for errors in DalvikInstruction execution
                    if (returnValue.getError() != null) {
                        throw returnValue.getError();
                    }
                    //if no errors, update values
                    frame = returnValue.getFrame();
                    method = returnValue.getMethod();
                    upperCodes = returnValue.getUpperCodes();
                    lowerCodes = returnValue.getLowerCodes();
                    codes = returnValue.getCodes();
                    toNode = returnValue.getNode();
                    //save node connection
                    createNewConnection(fromNode, toNode, currentInstruction);
                }
            } catch (Throwable e) {
                frame = handleThrowable(e, frame);
                method = frame.getMethod();
                lowerCodes = method.getOpcodes();
                upperCodes = method.getRegistercodes();
                codes = method.getIndex();
            }
        }
    }
}