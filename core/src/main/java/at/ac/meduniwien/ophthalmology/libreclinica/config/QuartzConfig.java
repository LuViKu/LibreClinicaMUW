package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.quartz.JobListener;
import org.quartz.TriggerListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;

import at.ac.meduniwien.ophthalmology.libreclinica.job.JobExecutionExceptionListener;
import at.ac.meduniwien.ophthalmology.libreclinica.job.JobTriggerListener;
import at.ac.meduniwien.ophthalmology.libreclinica.job.OpenClinicaSchedulerFactoryBean;

/**
 * Phase C.5: Java replacement for applicationContext-core-scheduler.xml.
 * <p>
 * Wires the Quartz {@link OpenClinicaSchedulerFactoryBean} bean.
 * <p>
 * Notes preserved from the XML's inline comment:
 * <ul>
 *   <li>Do <em>not</em> set {@code org.quartz.jobStore.class} explicitly. When
 *       a {@code dataSource} is also set, Spring's
 *       {@code SchedulerFactoryBean.createScheduler()} needs to install its own
 *       {@code LocalDataSourceJobStore} wired to the registered
 *       {@code DBConnectionManager} name. Setting {@code jobStore.class} via
 *       Quartz properties bypasses Spring's wiring (Quartz instantiates a
 *       fresh, un-wired JobStore) and yields the runtime error: "DataSource
 *       name not set" at app startup.</li>
 *   <li>{@code depends-on="liquibase"} preserved via {@link DependsOn} so the
 *       Quartz schema tables exist before the scheduler tries to register.</li>
 * </ul>
 */
@Configuration
public class QuartzConfig {

    @Value("${org.quartz.jobStore.misfireThreshold}")
    private String misfireThreshold;

    @Value("${org.quartz.jobStore.driverDelegateClass}")
    private String driverDelegateClass;

    @Value("${org.quartz.jobStore.useProperties}")
    private String useProperties;

    @Value("${org.quartz.jobStore.tablePrefix}")
    private String tablePrefix;

    @Value("${org.quartz.threadPool.threadCount}")
    private String threadCount;

    @Value("${org.quartz.threadPool.threadPriority}")
    private String threadPriority;

    @Bean(destroyMethod = "destroy")
    @DependsOn("liquibase")
    public OpenClinicaSchedulerFactoryBean schedulerFactoryBean(
            DataSource dataSource,
            PlatformTransactionManager transactionManager) {

        OpenClinicaSchedulerFactoryBean factory = new OpenClinicaSchedulerFactoryBean();
        factory.setSchedulerName("public");
        factory.setAutoStartup(true);
        factory.setDataSource(dataSource);
        factory.setTransactionManager(transactionManager);

        Properties quartzProps = new Properties();
        quartzProps.setProperty("org.quartz.jobStore.misfireThreshold", misfireThreshold);
        quartzProps.setProperty("org.quartz.jobStore.driverDelegateClass", driverDelegateClass);
        quartzProps.setProperty("org.quartz.jobStore.useProperties", useProperties);
        quartzProps.setProperty("org.quartz.jobStore.tablePrefix", tablePrefix);
        quartzProps.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        quartzProps.setProperty("org.quartz.threadPool.threadCount", threadCount);
        quartzProps.setProperty("org.quartz.threadPool.threadPriority", threadPriority);
        factory.setQuartzProperties(quartzProps);

        factory.setApplicationContextSchedulerContextKey("applicationContext");
        factory.setGlobalJobListeners(new JobListener[]{new JobExecutionExceptionListener()});
        factory.setGlobalTriggerListeners(new TriggerListener[]{new JobTriggerListener()});

        return factory;
    }
}
