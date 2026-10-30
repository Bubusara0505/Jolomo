package kg.jolomo.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ночное удаление просроченных синтетических данных (03:10 Asia/Bishkek). Реальных людей не касается. */
@Component
@ConditionalOnProperty(name = "jolomo.privacy.purge.enabled", havingValue = "true", matchIfMissing = true)
class SyntheticPurgeJob {

    private static final Logger LOG = LoggerFactory.getLogger(SyntheticPurgeJob.class);

    private final SyntheticDataService synthetic;

    SyntheticPurgeJob(SyntheticDataService synthetic) {
        this.synthetic = synthetic;
    }

    @Scheduled(cron = "${jolomo.privacy.purge.cron:0 10 3 * * *}", zone = "Asia/Bishkek")
    void run() {
        try {
            var r = synthetic.purgeExpired();
            LOG.info("Очистка синтетики: удалено {}, пропущено по legal hold {}, пропущено из-за связей {}, ошибок файлов {}",
                    r.deleted(), r.skippedLegalHold(), r.skippedLinked(), r.fileErrors());
        } catch (Exception e) {
            // Сбой не должен останавливать остальные задания; его видно в журнале и в алертах заданий (SP13-03).
            LOG.error("Очистка синтетики не удалась", e);
        }
    }
}
