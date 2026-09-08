package in.gov.sarthi.result.config;

import in.gov.sarthi.result.model.ApplicationStatusHistory;
import in.gov.sarthi.result.model.Result;
import in.gov.sarthi.result.repository.ApplicationStatusHistoryRepository;
import in.gov.sarthi.result.repository.ResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Replaces V1__init_schema.sql's old plaintext SQL seed data. Now that
 * Result.rollNumber/name are encrypted via PiiEncryptionService
 * (@Convert), Flyway can't produce valid ciphertext directly — it has no
 * access to the encryption key. Going through the JPA repository here
 * means the same converters the app uses for every other write also run
 * for this seed data, so it round-trips correctly.
 *
 * Guarded by both an empty-table check and a property, so this never
 * re-seeds on every restart and can be turned off entirely for a real
 * deployment (where you'd load real results some other way).
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final ResultRepository resultRepository;
    private final ApplicationStatusHistoryRepository statusHistoryRepository;
    private final boolean seedEnabled;

    public DemoDataSeeder(
            ResultRepository resultRepository,
            ApplicationStatusHistoryRepository statusHistoryRepository,
            @Value("${sarthi.seed-demo-data:true}") boolean seedEnabled) {
        this.resultRepository = resultRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        if (resultRepository.count() > 0) {
            return; // already seeded (or this is a real deployment with real data) — never overwrite
        }

        log.info("Seeding demo result data (sarthi.seed-demo-data=true, results table empty)");

        seedOne("26104578912", "NEET-UG-2026", "Aarav Sharma", 612, "PASS");
        seedOne("26104578913", "NEET-UG-2026", "Diya Patel", 545, "PASS");
        seedOne("26104578914", "NEET-UG-2026", "Kabir Singh", 298, "FAIL");
        seedOne("26205671201", "JEE-MAIN-2026", "Ishaan Reddy", 264, "PASS");
        seedOne("26205671202", "JEE-MAIN-2026", "Ananya Iyer", 189, "FAIL");
    }

    private void seedOne(String rollNumber, String examId, String name, int score, String status) {
        Instant now = Instant.now();
        resultRepository.save(new Result(rollNumber, examId, name, score, status, now));
        statusHistoryRepository.save(new ApplicationStatusHistory(
                rollNumber, examId, ApplicationStatusHistory.RESULT_DECLARED, "Result already declared"));
    }
}
