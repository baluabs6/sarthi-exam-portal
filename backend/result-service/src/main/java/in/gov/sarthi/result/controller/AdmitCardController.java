package in.gov.sarthi.result.controller;

import in.gov.sarthi.result.model.Result;
import in.gov.sarthi.result.repository.ResultRepository;
import in.gov.sarthi.result.security.TicketReplayGuard;
import in.gov.sarthi.result.security.TicketService;
import in.gov.sarthi.result.util.PiiMasker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generates a simple admit card / hall ticket PDF, gated by the exact
 * same signed, single-use admission ticket used for result lookup
 * (TicketService + TicketReplayGuard) — the candidate still has to come
 * through the queue-and-ticket flow, just as for a result.
 *
 * Candidate details (name, exam) are read from the same `results` table
 * already seeded for this demo, since there's no separate applicant
 * profile table in this codebase. In a real system these would come
 * from an actual applications/registrations table populated well before
 * results exist — this is a deliberate simplification, called out here
 * rather than silently assumed.
 */
@RestController
public class AdmitCardController {

    private static final Logger log = LoggerFactory.getLogger(AdmitCardController.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final ResultRepository resultRepository;
    private final TicketService ticketService;
    private final TicketReplayGuard replayGuard;

    public AdmitCardController(ResultRepository resultRepository, TicketService ticketService, TicketReplayGuard replayGuard) {
        this.resultRepository = resultRepository;
        this.ticketService = ticketService;
        this.replayGuard = replayGuard;
    }

    @GetMapping("/api/admit-card/{rollNumber}")
    public ResponseEntity<?> getAdmitCard(
            @PathVariable String rollNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        String ticket = extractBearerToken(authorization);
        var verified = ticketService.verify(ticket);
        if (verified.isEmpty() || !verified.get().rollNumber().equals(rollNumber)) {
            log.warn("Admit card access denied for {} — invalid/mismatched ticket", PiiMasker.maskRollNumber(rollNumber));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "A valid admission ticket is required. Please join the queue to access this."
            ));
        }
        // Consumed under a separate "admit-card" tag from result lookup
        // (see TicketReplayGuard) — the same ticket can be redeemed once
        // for the result AND once for the admit card, since both are a
        // legitimate use of one genuine queue turn, rather than the
        // ticket being globally single-use across every resource.
        if (!replayGuard.tryConsume(verified.get().jti(), Duration.ofMinutes(15), "admit-card")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "This admission ticket has already been used. Please rejoin the queue for another look."
            ));
        }

        Result result = resultRepository.findByRollNumber(rollNumber).orElse(null);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "No record found for this roll number."));
        }

        byte[] pdf = renderAdmitCard(result);
        log.info("Admit card generated for {}", PiiMasker.maskRollNumber(rollNumber));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"admit-card-" + rollNumber + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private byte[] renderAdmitCard(Result result) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float margin = 60;
                float y = page.getMediaBox().getHeight() - margin;

                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                content.newLineAtOffset(margin, y);
                content.showText("SARTHI PORTAL — ADMIT CARD");
                content.endText();
                y -= 40;

                content.setStrokingColor(0.6f, 0.6f, 0.6f);
                content.moveTo(margin, y);
                content.lineTo(page.getMediaBox().getWidth() - margin, y);
                content.stroke();
                y -= 30;

                y = writeField(content, margin, y, "Candidate Name", result.getName());
                y = writeField(content, margin, y, "Roll Number", result.getRollNumber());
                y = writeField(content, margin, y, "Examination", result.getExamId());
                y = writeField(content, margin, y, "Issued On", DATE_FORMAT.format(java.time.LocalDate.now()));

                y -= 20;
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE), 10);
                content.newLineAtOffset(margin, y);
                content.showText("Carry a valid original photo ID along with this admit card to the exam center.");
                content.endText();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate admit card PDF", e);
        }
    }

    private float writeField(PDPageContentStream content, float x, float y, String label, String value) throws java.io.IOException {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 11);
        content.newLineAtOffset(x, y);
        content.showText(label + ":");
        content.endText();

        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        content.newLineAtOffset(x + 160, y);
        content.showText(value == null ? "-" : value);
        content.endText();

        return y - 24;
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring("Bearer ".length()).trim();
    }
}
