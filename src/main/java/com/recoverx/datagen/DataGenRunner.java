package com.recoverx.datagen;

import com.recoverx.domain.FailedTransaction;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs only when {@code --generate} is passed, so the web app and the data
 * generator live in one artifact without one getting in the other's way.
 */
@Component
public class DataGenRunner implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        boolean requested = false;
        for (String arg : args) {
            if ("--generate".equals(arg)) {
                requested = true;
                break;
            }
        }
        if (!requested) {
            return;
        }

        GeneratorConfig config = GeneratorConfig.fromArgs(args);
        List<FailedTransaction> batch = new SyntheticDataGenerator(config).generate();
        new DatasetWriter().write(batch, config);

        long totalPaise = batch.stream().mapToLong(FailedTransaction::amountPaise).sum();
        long recoverablePaise = batch.stream()
                .filter(t -> t.truth().recoverable())
                .mapToLong(FailedTransaction::amountPaise).sum();

        System.out.printf("generated %d transactions -> %s%n", batch.size(), config.outDir().toAbsolutePath());
        System.out.printf("  seed              : %d%n", config.seed());
        System.out.printf("  total failed value: Rs %.2f%n", totalPaise / 100.0);
        System.out.printf("  oracle ceiling    : Rs %.2f (%.1f%%)%n",
                recoverablePaise / 100.0, 100.0 * recoverablePaise / totalPaise);
        System.out.printf("  do-not-retry traps: %d%n",
                batch.stream().filter(t -> t.truth().mustNotRetry()).count());
    }
}
