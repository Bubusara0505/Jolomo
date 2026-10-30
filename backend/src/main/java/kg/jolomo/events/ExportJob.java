package kg.jolomo.events;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ночной запуск выгрузки за вчерашние сутки по Asia/Bishkek. Включается jolomo.export.enabled=true вместе с ключом. */
@Component
@ConditionalOnProperty(name = "jolomo.export.enabled", havingValue = "true")
class ExportJob {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJob.class);

    private final ExportService exports;

    ExportJob(ExportService exports) {
        this.exports = exports;
    }

    @Scheduled(cron = "${jolomo.export.cron:0 30 2 * * *}", zone = "Asia/Bishkek")
    void run() {
        LocalDate day = LocalDate.now(ExportService.ZONE).minusDays(1);
        try {
            var file = exports.writeFor(day);
            LOG.info("Ночная выгрузка за {} записана: {}", day, file.getFileName());
        } catch (Exception e) {
            // Сбой выгрузки не должен останавливать остальные задания; его видно в журнале и в алертах заданий (SP13-03).
            LOG.error("Ночная выгрузка за {} не удалась", day, e);
        }
    }
}
