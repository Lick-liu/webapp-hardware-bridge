package tigerworkshop.webapphardwarebridge.websocketservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Scaling;
import tigerworkshop.webapphardwarebridge.dtos.Config;
import tigerworkshop.webapphardwarebridge.dtos.NotificationDTO;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServerInterface;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServiceInterface;
import tigerworkshop.webapphardwarebridge.responses.PrintDocument;
import tigerworkshop.webapphardwarebridge.responses.PrintResult;
import tigerworkshop.webapphardwarebridge.services.ConfigService;
import tigerworkshop.webapphardwarebridge.services.DocumentService;
import tigerworkshop.webapphardwarebridge.services.PrintServiceDiscoveryService;
import tigerworkshop.webapphardwarebridge.services.PrinterMappingService;
import tigerworkshop.webapphardwarebridge.services.PrintJobReplayGuard;
import tigerworkshop.webapphardwarebridge.services.VirtualPdfPrinterSupport;
import tigerworkshop.webapphardwarebridge.utils.AnnotatedPrintable;
import tigerworkshop.webapphardwarebridge.utils.ImagePrintable;

import javax.imageio.ImageIO;
import javax.print.*;
import java.awt.*;
import java.awt.print.*;
import java.io.File;
import java.util.Optional;

@Log4j2
public class PrinterWebSocketService implements WebSocketServiceInterface {
    private WebSocketServerInterface server;

    private static final ConfigService configService = ConfigService.getInstance();
    private static final DocumentService documentService = DocumentService.getInstance();
    private static final PrintServiceDiscoveryService printServiceDiscoveryService = PrintServiceDiscoveryService.getInstance();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final PrintJobReplayGuard replayGuard;
    private final PrintExecutor printExecutor;

    @FunctionalInterface
    interface PrintExecutor {
        PrintResult execute(PrintDocument printDocument) throws Exception;
    }

    public PrinterWebSocketService() {
        this(PrintJobReplayGuard.getInstance(), null);
    }

    PrinterWebSocketService(PrintJobReplayGuard replayGuard, PrintExecutor printExecutor) {
        this.replayGuard = replayGuard;
        this.printExecutor = printExecutor == null ? this::executePrintDocument : printExecutor;
        log.info("Starting PrinterWebSocketService");
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void messageToService(String message) {
        try {
            PrintDocument printDocument = objectMapper.readValue(message, PrintDocument.class);
            printDocument(printDocument);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public void messageToService(byte[] message) {
        log.error("PrinterWebSocketService onDataReceived: binary data not supported");
    }

    @Override
    public void onRegister(WebSocketServerInterface server) {
        this.server = server;
    }

    @Override
    public void onUnregister() {
        this.server = null;
    }

    @Override
    public String getChannel() {
        return "/printer";
    }

    /**
     * Prints a PrintDocument
     */
    public void printDocument(PrintDocument printDocument) throws Exception {
        PrintJobReplayGuard.Decision decision = replayGuard.begin(printDocument.getId());
        if (decision.action() == PrintJobReplayGuard.Action.IN_PROGRESS) {
            log.info("Ignoring concurrent replay for print job id={}; original execution is still running",
                    printDocument.getId());
            return;
        }
        if (decision.action() == PrintJobReplayGuard.Action.REPLAY_SUCCESS) {
            log.info("Replaying cached success for print job id={}", printDocument.getId());
            sendPrintResult(decision.cachedResult());
            return;
        }
        if (decision.action() == PrintJobReplayGuard.Action.REPLAY_RECONCILE) {
            log.warn("Print job id={} requires manual reconciliation after helper restart", printDocument.getId());
            sendPrintResult(decision.cachedResult());
            return;
        }
        if (decision.action() == PrintJobReplayGuard.Action.REJECT_INVALID_ID) {
            log.warn("Rejecting print job because its stable id exceeds {} characters",
                    PrintJobReplayGuard.MAX_STABLE_JOB_ID_LENGTH);
            sendPrintResult(new PrintResult(
                    false,
                    "本地打印任务 ID 过长，已在物理打印前拒绝任务",
                    printDocument.getId(),
                    null
            ));
            return;
        }
        if (decision.action() == PrintJobReplayGuard.Action.REJECT_PERSISTENCE) {
            log.error("Rejecting print job id={} because durable replay state is unavailable", printDocument.getId());
            sendPrintResult(new PrintResult(
                    false,
                    "本地打印助手无法安全保存防重状态，已在物理打印前拒绝任务",
                    printDocument.getId(),
                    null
            ));
            return;
        }
        if (decision.action() == PrintJobReplayGuard.Action.REJECT_CAPACITY) {
            log.warn("Rejecting print job id={} because the in-flight replay gate is full", printDocument.getId());
            sendPrintResult(new PrintResult(
                    false,
                    "本地打印助手正在处理过多未完成任务，请稍后重试",
                    printDocument.getId(),
                    null
            ));
            return;
        }

        PrintResult result;
        try {
            result = printExecutor.execute(printDocument);
        } catch (Exception exception) {
            log.error("Unexpected print executor failure for id={}", printDocument.getId(), exception);
            result = new PrintResult(false, exception.getMessage(), printDocument.getId(), null);
        }

        if (decision.action() == PrintJobReplayGuard.Action.EXECUTE) {
            if (Boolean.TRUE.equals(result.success)) {
                replayGuard.succeed(printDocument.getId(), result);
            } else {
                replayGuard.fail(printDocument.getId());
            }
        }
        sendPrintResult(result);
    }

    private void sendPrintResult(PrintResult result) throws Exception {
        server.messageToServer(getChannel(), objectMapper.writeValueAsString(result));
    }

    private PrintResult executePrintDocument(PrintDocument printDocument) throws Exception {
        log.info("Printing Document {}, {}", printDocument.getType(), printDocument.getUrl());

        PrinterSearchResult printerSearchResult = null;
        try {
            printerSearchResult = searchPrinterForType(printDocument.getType());

            server.messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Printing " + printDocument.getType(), printDocument.getUrl())));

            if (isRaw(printDocument)) {
                printRaw(printDocument, printerSearchResult);
            } else if (isImage(printDocument)) {
                printImage(printDocument, printerSearchResult);
            } else if (isPDF(printDocument)) {
                printPDF(printDocument, printerSearchResult);
            } else {
                throw new Exception("Unknown file type: " + printDocument.getUrl());
            }

            return new PrintResult(true, "Success", printDocument.getId(), printerSearchResult.getName());
        } catch (Exception e) {
            String errorMessage = e.getMessage();

            if (e instanceof PrinterAbortException) {
                errorMessage = "Printing aborted";
            }

            log.error("Print Error: {}, {}", e.getClass().getName(), errorMessage);

            if (!isRaw(printDocument)) {
                log.error("Print Error: Deleting downloaded document");
                documentService.deleteDocument(printDocument);
            }

            server.messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("ERROR", "Print Error " + printDocument.getType(), errorMessage)));

            return new PrintResult(false, errorMessage, printDocument.getId(), printerSearchResult != null ? printerSearchResult.getName() : null);
        }
    }

    /**
     * Return if PrintDocument is raw
     */
    private Boolean isRaw(PrintDocument printDocument) {
        return printDocument.getRawContent() != null && !printDocument.getRawContent().isEmpty();
    }

    /**
     * Return if PrintDocument is image
     */
    private Boolean isImage(PrintDocument printDocument) {
        String filename = FilenameUtils.getName(printDocument.getUrl());

        return filename.matches("^.*\\.(jpg|jpeg|png|gif)$");
    }

    /**
     * Return if PrintDocument is PDF
     */
    private Boolean isPDF(PrintDocument printDocument) {
        String filename = FilenameUtils.getName(printDocument.getUrl());

        return filename.matches("^.*\\.(pdf)$");
    }

    /**
     * Prints raw bytes to specified printer.
     */
    private void printRaw(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        log.debug("printRaw::{}", printDocument);
        long timeStart = System.currentTimeMillis();

        byte[] bytes = Base64.decodeBase64(printDocument.getRawContent());

        if (VirtualPdfPrinterSupport.isVirtualPdfPrinter(printerSearchResult.getName())) {
            printRawAsTextPage(printDocument, printerSearchResult, bytes);
            return;
        }

        DocPrintJob docPrintJob = printerSearchResult.getDocPrintJob();
        Doc doc = new SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        docPrintJob.print(doc, null);

        long timeFinish = System.currentTimeMillis();
        log.info("printRaw finished in {} ms", timeFinish - timeStart);
    }

    /**
     * Renders raw bytes as text for virtual PDF printers. Sending raw ESC/POS bytes directly to
     * Microsoft Print to PDF creates a file containing those bytes, not a valid PDF.
     */
    private void printRawAsTextPage(PrintDocument printDocument, PrinterSearchResult printerSearchResult, byte[] bytes) throws PrinterException {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(printerSearchResult.getDocPrintJob().getPrintService());
        job.setJobName(printDocument.getType());
        job.setCopies(printDocument.getQty());

        PageFormat pageFormat = getPageFormat(job, printerSearchResult);
        job.setPrintable(VirtualPdfPrinterSupport.createRawTextPrintable(bytes), pageFormat);
        job.print();
    }

    /**
     * Prints image to specified printer.
     */
    private void printImage(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        log.debug("printImage::{}", printDocument);

        File file = documentService.prepareDocument(printDocument);
        String path = file.getPath();
        String filename = file.getName();

        long timeStart = System.currentTimeMillis();

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(printerSearchResult.getDocPrintJob().getPrintService());

        PageFormat pageFormat = getPageFormat(job, printerSearchResult);

        Image image = ImageIO.read(new File(path));

        Book book = new Book();
        AnnotatedPrintable printable = new AnnotatedPrintable(new ImagePrintable(image));

        for (AnnotatedPrintable.AnnotatedPrintableAnnotation printDocumentExtra : printDocument.getExtras()) {
            printable.addAnnotation(printDocumentExtra);
        }

        book.append(printable, pageFormat);

        job.setPageable(book);
        job.setJobName(filename);
        job.setCopies(printDocument.getQty());
        job.print();

        long timeFinish = System.currentTimeMillis();

        log.info("printImage {} finished in {} ms", filename, timeFinish - timeStart);
    }

    /**
     * Prints PDF to specified printer.
     */
    private void printPDF(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        log.debug("printPDF::{}", printDocument);

        File file = documentService.prepareDocument(printDocument);
        String path = file.getPath();
        String filename = file.getName();

        long timeStart = System.currentTimeMillis();

        DocPrintJob docPrintJob = printerSearchResult.getDocPrintJob();

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(docPrintJob.getPrintService());

        PageFormat pageFormat = getPageFormat(job, printerSearchResult);

        try (PDDocument document = PDDocument.load(new File(path))) {
            Book book = new Book();
            for (int i = 0; i < document.getNumberOfPages(); i += 1) {
                // Rotate Page Automatically
                PageFormat eachPageFormat = (PageFormat) pageFormat.clone();

                if (printerSearchResult.getMapping().isAutoRotate()) {
                    if (document.getPage(i).getCropBox().getWidth() > document.getPage(i).getCropBox().getHeight()) {
                        log.debug("Auto rotation result: LANDSCAPE");
                        eachPageFormat.setOrientation(PageFormat.LANDSCAPE);
                    } else {
                        log.debug("Auto rotation result: PORTRAIT");
                        eachPageFormat.setOrientation(PageFormat.PORTRAIT);
                    }
                }

                PDFPrintable pdfPrintable = new PDFPrintable(document, Scaling.SHRINK_TO_FIT, false, printerSearchResult.getMapping().getForceDPI());

                // Annotate Printable
                AnnotatedPrintable annotatedPrintable = new AnnotatedPrintable(pdfPrintable);
                for (AnnotatedPrintable.AnnotatedPrintableAnnotation printDocumentExtra : printDocument.getExtras()) {
                    annotatedPrintable.addAnnotation(printDocumentExtra);
                }

                book.append(annotatedPrintable, eachPageFormat);
            }

            job.setPageable(book);
            job.setJobName(filename);
            job.setCopies(printDocument.getQty());
            job.print();

            long timeFinish = System.currentTimeMillis();

            log.info("printPDF {} finished in {} ms", path, timeFinish - timeStart);
        }
    }

    private PageFormat getPageFormat(PrinterJob job, PrinterSearchResult printerSearchResult) {
        final PageFormat pageFormat = job.defaultPage();

        log.debug("PageFormat Size: {} x {}", pageFormat.getWidth(), pageFormat.getHeight());
        log.debug("PageFormat Imageable Size:{} x {}, XY: {}, {}", pageFormat.getImageableWidth(), pageFormat.getImageableHeight(), pageFormat.getImageableX(), pageFormat.getImageableY());
        log.debug("Paper Size: {} x {}", pageFormat.getPaper().getWidth(), pageFormat.getPaper().getHeight());
        log.debug("Paper Imageable Size: {} x {}, XY: {}, {}", pageFormat.getPaper().getImageableWidth(), pageFormat.getPaper().getImageableHeight(), pageFormat.getPaper().getImageableX(), pageFormat.getPaper().getImageableY());

        // Reset Imageable Area
        if (printerSearchResult.getMapping().isResetImageableArea()) {
            log.debug("PageFormat reset enabled");
            Paper paper = pageFormat.getPaper();
            paper.setImageableArea(0, 0, paper.getWidth(), paper.getHeight());
            pageFormat.setPaper(paper);
        }

        log.debug("Final Paper Size: {} x {}", pageFormat.getPaper().getWidth(), pageFormat.getPaper().getHeight());
        log.debug("Final Paper Imageable Size: {} x {}, XY: {}, {}", pageFormat.getPaper().getImageableWidth(), pageFormat.getPaper().getImageableHeight(), pageFormat.getPaper().getImageableX(), pageFormat.getPaper().getImageableY());

        return pageFormat;
    }

    /**
     * Get PrinterSearchResult for specified type
     */
    private PrinterSearchResult searchPrinterForType(String type) throws PrinterException {
        Optional<Config.PrinterMapping> printerMappingOptional =
                PrinterMappingService.findMapping(configService.getConfig(), type);

        if (printerMappingOptional.isPresent()) {
            Config.PrinterMapping printerMapping = printerMappingOptional.get();
            Optional<PrintService> printServiceOptional = printServiceDiscoveryService.findPrintServiceByName(printerMapping.getName());

            if (printServiceOptional.isPresent()) {
                PrintService printService = printServiceOptional.get();
                log.info("Sending print job type: {} to printer: {}", type, printService.getName());

                return new PrinterSearchResult(printService.getName(), printerMapping, printService.createPrintJob(), false);
            }
        }

         if (configService.getConfig().getPrinter().isAutoAddUnknownType()) {
             // Add unknown type does not already exist
             if (configService.getConfig().getPrinter().getMappings().stream().noneMatch(it -> it.getType().equals(type))) {
                 configService.addPrintTypeToList(type);
             }
        }

         if (configService.getConfig().getPrinter().isFallbackToDefault()) {
             log.info("No mapped print job type: {}, falling back to default printer", type);

             PrintService printService = PrintServiceLookup.lookupDefaultPrintService();

             if (printService == null) {
                 throw new PrinterException("No default printer found");
             }

             return new PrinterSearchResult(printService.getName(), new Config.PrinterMapping(), printService.createPrintJob(), true);
        }

         throw new PrinterException("No matched printer: " + type);
    }

    @Getter
    @AllArgsConstructor
    private static class PrinterSearchResult {
        private String name;
        private Config.PrinterMapping mapping;
        private DocPrintJob docPrintJob;
        private Boolean isDefault;
    }
}
