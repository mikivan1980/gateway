package com.mikivan.gateway.dicom;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.dcm4che3.tool.common.CLIUtils;
import org.dcm4che3.util.SafeClose;
import org.dcm4che3.util.TagUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;


public class SingleServer extends Device {

    //параметры взяты из OHIF
    private static final String[] r_OPTS_FOR_STUDYES = new String[] {
            "0020000D", "00080020", "00080030", "00080050", "00080090", "00100010", "00100020",
            "00100030", "00100040", "00200010", "00201206", "00201208", "00081030", "00080060",
            "00080061"};

    private static final String[] r_OPTS_FOR_IMAGES  = new String[]{
            "00100010", "00100020", "00101010", "00101020", "00101030", "00080050", "00080020",
            "00080061", "00081030", "00201208", "0020000D", "00080080", "0020000E", "0008103E",
            "00080060", "00200011", "00080021", "00080031", "00080008", "00080016", "00080018",
            "00200013", "00200032", "00200037", "00200052", "00201041", "00280002", "00280004",
            "00280006", "00280010", "00280011", "00280030", "00280034", "00280100", "00280101",
            "00280102", "00280103", "00280106", "00280107", "00281050", "00281051", "00281052",
            "00281053", "00281054", "00200062", "00185101", "0008002A", "00280008", "00280009",
            "00181063", "00181065", "00180050", "00282110", "00282111", "00282112", "00282114",
            "00180086", "00180010" };


    // Context Presentation UID
    private static String cuidFind = UID.StudyRootQueryRetrieveInformationModelFIND;
    private static String cuidMove = UID.StudyRootQueryRetrieveInformationModelMOVE;

    // Transfer Syntax
    private static String[] tsuid =
            new String[]{ UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian, UID.ExplicitVRBigEndianRetired};

    private final Connection bindSCP = new Connection();
    private final Connection remoteSCP = new Connection();

    private final ApplicationEntity ae = new ApplicationEntity("aeTitle");

    private final AAssociateRQ rq = new AAssociateRQ();


    private int priority;
    private int cancelAfter;

    private OutputStream out;

    public static long count = 0;
    public static Map<String, byte[]> dataList = new HashMap<String,  byte[]>();


    private boolean isConstructorWithArgs = false;

    //******************************************************************************************************************
    //******************************************************************************************************************
    //******************************************************************************************************************
    private class CFineSCU extends DimseRSPHandler {

        private CFineSCU(int msgId) {
            super(msgId);
        }

        int cancelAfter = SingleServer.this.cancelAfter;
        int numMatches;

        //call this function from -> \dcm4che-net-5.10.6.jar!\org\dcm4che3\net\PDUDecoder.class: 418
        @Override
        public void onDimseRSP(Association as, Attributes cmd, Attributes data) {

//            System.out.println("numMatches+++++++++++++++++++++++++++++++++++++++++   " + numMatches);
//            System.out.println("cancelAfter++++++++++++++++++++++++++++++++++++++++   " + cancelAfter);

            super.onDimseRSP(as, cmd, data);

            int status = cmd.getInt(Tag.Status, -1);
            System.out.println( "[CFindSCU][STATUS]/> " + Integer.toHexString(status) );
            System.out.println( "[CFindSCU][STATE]/>  " + as.getState().toString() );

            if (Status.isPending(status)) {

                Dimse dimse = Dimse.valueOf(cmd.getInt(256, 0));
                System.out.println("[CFindSCU][DIMSE]/>  " + dimse.toString(cmd,1, UID.ImplicitVRLittleEndian));

                SingleServer.this.printAttributes(cmd);    System.out.println();
                SingleServer.this.printAttributes(data);   System.out.println();

                SingleServer.this.onResultCFindSCU(data); // в этой функции преобразуем данные к json

                ++numMatches;
                if (cancelAfter != 0 && numMatches >= cancelAfter)
                    try {
                        System.out.println("-- cancelAfter -- cancelAfter -- cancelAfter -- cancelAfter -- cancelAfter --");
                        cancel(as);
                        cancelAfter = 0;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
            }
        }
    }

    //******************************************************************************************************************
    //******************************************************************************************************************
    //******************************************************************************************************************
    private class CMoveSCU extends DimseRSPHandler {

        private CMoveSCU(int msgId) {
            super(msgId);
        }

        @Override
        public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
            super.onDimseRSP(as, cmd, data);

            int status = cmd.getInt(Tag.Status, -1);
            System.out.println( "[CMoveSCU][STATUS]/> " + Integer.toHexString(status) );
            System.out.println( "[CMoveSCU][STATE]/>  " + as.getState().toString() );

            Dimse dimse = Dimse.valueOf(cmd.getInt(256, 0));
            System.out.println( "[CMoveSCU][DIMSE]/>  " + dimse.toString(cmd,1, UID.ImplicitVRLittleEndian));
        }
    }

    //******************************************************************************************************************
    //******************************************************************************************************************
    //******************************************************************************************************************
    private class CStoreSCP extends BasicCStoreSCP {

        CStoreSCP(String... sopClasses){
            super(sopClasses);
        }

        @Override
        protected void store(Association as, PresentationContext pc,
                             Attributes rq, PDVInputStream data, Attributes rsp)
                throws IOException {

            rsp.setInt(Tag.Status, VR.US, 0);


/* диком объекты (файлы) находящиеся в хранилище диком сервера имеют
* ts = UID.ImplicitVRLittleEndian = "1.2.840.10008.1.2";
* так же поступает и CStoreSCP, когда файл записыватся на диск или wado, то
* диком объект имеет ts = UID.ExplicitVRLittleEndian = = "1.2.840.10008.1.2.1";
* OHIF ест все форматы, так что можно не преобразовывать
* */

            // свой код обработки принятых данных
            // помещаем наши ланные в статические коллекции т.е. одну на все вызовы.
            String cuid = rq.getString(Tag.AffectedSOPClassUID);
            String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
            String tsuid = pc.getTransferSyntax();

            System.out.println("tsuid ===> " + tsuid);

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            ////BufferedOutputStream outBuffer = new BufferedOutputStream(out);
            ////DicomOutputStream dicomVirtualFile = new DicomOutputStream(outBuffer , UID.ExplicitVRLittleEndian);

            DicomOutputStream dicomVirtualFile = new DicomOutputStream(out , UID.ExplicitVRLittleEndian);
            //DicomOutputStream dicomVirtualFile = new DicomOutputStream(new File("d:\\workshop\\INTELLOGIC\\development\\cstore-output\\out" + count + ".dcm"));

            try {

                try{

                    dicomVirtualFile.writeFileMetaInformation( as.createFileMetaInformation(iuid, cuid, tsuid) );
                    data.copyTo(dicomVirtualFile);

                } finally {

                    dataList.put(iuid, out.toByteArray());

                    System.out.println("out.toByteArray().length ===> " + out.toByteArray().length);

                    SafeClose.close(dicomVirtualFile);
                    out.close();

                    System.out.println("count ==>>> " + count + ";    dataList.size() = " + dataList.size());

                    count++;
                    return;

                } } catch (Exception e) {
                throw new DicomServiceException(Status.ProcessingFailure, e);
            }
        }
    };


    public final void setPriority(int priority) { this.priority = priority;}
    public final void setCancelAfter(int cancelAfter) {
        this.cancelAfter = cancelAfter;
    }

    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    public SingleServer( String[] b, String[] c, String[] opts) throws IOException {

        //super("findscu"); ? работает без инитц. суперкласса

        this.bindSCP.setHostname(b[1]);
        this.bindSCP.setPort(Integer.parseInt(b[2]));

        this.remoteSCP.setHostname(c[1]);
        this.remoteSCP.setPort(Integer.parseInt(c[2]));
        //this.remote.setTlsProtocols(this.bindSCU.getTlsProtocols());
        //this.remote.setTlsCipherSuites(this.bindSCU.getTlsCipherSuites());

        configure(this.bindSCP, opts); //замена CLIUtils.configure(this.bindSCU, cl); //настройки proxy, есть в CLIUtils.configureConnect

        this.ae.setAETitle(b[0]);
        this.ae.addConnection(bindSCP);
        this.ae.setAssociationAcceptor(true);
        this.ae.addTransferCapability(
            new TransferCapability(null, "*", TransferCapability.Role.SCP,"*"));

        this.rq.setCalledAET(c[0]);
        this.rq.setCallingAET(b[0]);
        this.rq.addPresentationContext(new PresentationContext(1, cuidFind, tsuid));
        this.rq.addPresentationContext(new PresentationContext(2, cuidMove, tsuid));

        this.setPriority(0);

        // через сколько направленных с удаленного пакса записей отменить ассоциацию, ноль не отменять, принять все найденные записи
        this.setCancelAfter(0);

        this.setDimseRQHandler(createServiceRegistry());

        this.isConstructorWithArgs = true;
    }

    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    public boolean on(){
        try{
            if(this.isConstructorWithArgs) {

                this.addConnection(bindSCP);
                this.addApplicationEntity(ae);

                //this.setExecutor2(Executors.newSingleThreadExecutor());
                this.setExecutor2( Executors.newCachedThreadPool() );
                this.setScheduledExecutor(Executors.newSingleThreadScheduledExecutor());

                this.bindConnections();

                return true;
            }else{
                return false;
            }
        }catch (Exception ex){
            System.out.println("[ERROR]/>  SingleServer.on() -> " + ex.toString());
            return false;
        }
    }

    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    public boolean off(){
        try {
            if (isConstructorWithArgs) {

                this.unbindConnections();

                this.getExecutor2().shutdown();
                this.getScheduledExecutor().shutdown();

                this.isConstructorWithArgs = false;

                return true;
            }else{
                return false;
            }
        }catch(Exception ex){
            System.out.println("[ERROR]/>  SingleServer.off() -> " + ex.toString());
            return false;
        }
    }

    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    public String doFind(String[] m, String findLevel) throws  Exception{
        if(this.isConstructorWithArgs){

            Attributes keys = new Attributes();

            // составляем аттрибуты для отправки в запросе
            keys.setString(Tag.QueryRetrieveLevel, VR.CS, findLevel);

            switch ( (findLevel.equals("STUDY") ? 1: findLevel.equals("IMAGE") ? 2: 0) )
            {
                case 0 : return null;
                case 1 : CLIUtils.addEmptyAttributes(keys, r_OPTS_FOR_STUDYES);
                case 2 : CLIUtils.addEmptyAttributes(keys, r_OPTS_FOR_IMAGES);
            }

            CLIUtils.addAttributes(keys, m);

            Association as = ae.connect(bindSCP, remoteSCP, rq);

            DimseRSPHandler rspHandler = new SingleServer.CFineSCU( as.nextMessageID() );
            as.cfind( cuidFind, priority, keys, null, rspHandler);

            if (as.isReadyForDataTransfer()) {
                as.waitForOutstandingRSP();  // обработка получаемых данных в onDimseRSP из rspHandler
                as.release();
            }

            as.waitForSocketClose();


            //вывод
            String output;
            if( this.out == null )
                output = null;
            else output = this.out.toString();

            SafeClose.close(this.out);
            this.out = null;

            return output;
        }
        else{
            return null;
        }
    }

    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    public boolean doMove(String[] m, String moveLevel) throws  Exception{
        if(this.isConstructorWithArgs){

            Attributes keys = new Attributes();

            // составляем аттрибуты для отправки в запросе
            keys.setString(Tag.QueryRetrieveLevel, VR.CS, moveLevel);
            CLIUtils.addAttributes(keys, m);

            Association as = ae.connect(bindSCP, remoteSCP, rq);

            DimseRSPHandler rspHandler = new SingleServer.CMoveSCU( as.nextMessageID() );
            as.cmove( cuidMove, priority, keys, null, ae.getAETitle(), rspHandler);

            if (as.isReadyForDataTransfer()) {
                as.waitForOutstandingRSP();
                as.release();
            }

            as.waitForSocketClose();

            return true;


        }
        else{
            return false;
        }
    }
    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------


    //@SuppressWarnings("unchecked")
    public static void main(String[] args) {
//======================================================================================================================
        try{
            System.out.println("this run ... DICOM Single Server");

//            String[] bind   = { "IVAN",    "192.168.121.101", "4006"};//строгий порядок
//            String[] remote = { "WATCHER", "192.168.121.100", "4006"};//строгий порядок
            String[] bind   = { "IVAN",   "192.168.0.74", "4006"};//строгий порядок
            String[] remote = { "PACS01", "192.168.0.55", "4006"};//строгий порядок
            String[] opts   = {};
            String[] mFindStudy = { "StudyDate", "20111004-20171004", "ModalitiesInStudy", "CT"};
            String[] mFindImage = {"0020000D", "1.2.840.113704.1.111.4156.1367430813.2"};
            String[] mMoveStudy = { "StudyInstanceUID", "1.2.840.113619.2.134.1762938020.1589.1356408822.224"};

            SingleServer main = new SingleServer(bind, remote, opts);


            main.on();
            System.out.println("on();");

            String xsltOutput = main.doFind(mFindStudy, "STUDY");
            System.out.println("doFind(); st;");

            String xsltOutput2 = main.doFind(mFindImage, "IMAGE");
            System.out.println("doFind(); im");


            main.doMove(mMoveStudy,"STUDY");
            System.out.println("doMove(); st");

            main.off();
            System.out.println("off();");

            if( xsltOutput == null )
                System.out.println("xsltOutput == null");
            else System.out.println(xsltOutput);

        } catch (Exception ex) {
            System.err.println("Single Server: " + ex.getMessage());
            ex.printStackTrace();
            System.exit(2);
        }
    }
//======================================================================================================================

    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    private static void configure(Connection conn, String[] opts) throws IOException {
        //пока сделаем все по умолчанию, предполагая список опций в - opts
        //каждый ключ на своем строгом месте в массиве т.е. строгий порядок
        conn.setReceivePDULength(16378);  //"max-pdulen-rcv"
        conn.setSendPDULength(16378);     //"max-pdulen-snd"

        //используем не асинхронный режим if (cl.hasOption("not-async")) {
        conn.setMaxOpsInvoked(1);         //"max-ops-invoked"
        conn.setMaxOpsPerformed(1);       //"max-ops-performed"
//      } else {
//          conn.setMaxOpsInvoked(getIntOption(cl, "max-ops-invoked", 0));
//          conn.setMaxOpsPerformed(getIntOption(cl, "max-ops-performed", 0));
//      }

        conn.setPackPDV(false);           //"not-pack-pdv"
        conn.setConnectTimeout(0);        //"connect-timeout"
        conn.setRequestTimeout(0);        //"request-timeout"
        conn.setAcceptTimeout(0);         //"accept-timeout"
        conn.setReleaseTimeout(0);        //"release-timeout"
        conn.setResponseTimeout(0);       //"response-timeout"
        conn.setRetrieveTimeout(0);       //"retrieve-timeout"
        conn.setIdleTimeout(0);           //"idle-timeout"
        conn.setSocketCloseDelay(50);     //"soclose-delay"
        conn.setSendBufferSize(0);        //"sosnd-buffer"
        conn.setReceiveBufferSize(0);     //"sorcv-buffer"
        conn.setTcpNoDelay(false);        //"tcp-delay"

        // пока без применения TLS протокола
        //configureTLS(conn, cl);
    }

    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    private void onResultCFindSCU(Attributes data) {
        try {
            if (out == null) {
                out = new ByteArrayOutputStream();
            } else {
                DicomOutputStream dos = new DicomOutputStream(out, UID.ImplicitVRLittleEndian);
                dos.writeDataset(null, data);
            }
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
            SafeClose.close(out);
            out = null;
        }
    }

    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    private DicomServiceRegistry createServiceRegistry() {
        DicomServiceRegistry serviceRegistry = new DicomServiceRegistry();
        serviceRegistry.addDicomService(new BasicCEchoSCP());
        serviceRegistry.addDicomService(new CStoreSCP("*"));
        return serviceRegistry;
    }

    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    private void printAttributes( Attributes instanceAttr ){

        int nAttr = instanceAttr.size();
        for(int i = 0; i < nAttr; i++){
            int Tag_i = instanceAttr.tags()[i];
            String Tag_VR = instanceAttr.getVR(Tag_i).toString();
            System.out.print(
                    "[Attribute]/> " + "[" + nAttr + ", " + i + "] -> " +
                            TagUtils.toHexString(Tag_i) + " :->  " + TagUtils.toString(Tag_i) + "  :  " + Tag_VR + "  >  "
            );

            if( Tag_VR.equals("DA")||
                    Tag_VR.equals("PN")||
                    Tag_VR.equals("UI")||
                    Tag_VR.equals("CS")||
                    Tag_VR.equals("TM")||
                    Tag_VR.equals("US")
                    ){
                System.out.println(instanceAttr.getString( Tag_i ));
            }else{
                System.out.println();
            }
        }
    }

    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
}
