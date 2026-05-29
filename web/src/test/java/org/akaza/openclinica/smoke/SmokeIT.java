/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.smoke;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Base class for Phase B.4 jmesa-replacement smoke tests. Spins up a
 * Selenium ChromeDriver against a manually-started LibreClinica
 * instance and exposes a login helper. Subclasses navigate to a
 * specific page and assert visual behaviour.
 *
 * <h2>Operator usage</h2>
 *
 * Smoke tests live in the {@code smoke-tests} Maven profile (not in
 * the default unit or integration profiles). They run only when
 * explicitly activated and require:
 *
 * <ol>
 *   <li>A locally-running LibreClinica stack (e.g.
 *       {@code docker compose up} from the repo root).</li>
 *   <li>A reachable Chrome binary on the host. Selenium 4 manages the
 *       chromedriver download automatically.</li>
 *   <li>Override the target URL via {@code -Dsmoke.base.url=...} if
 *       the stack runs elsewhere. The default is the compose stack's
 *       {@code http://localhost:8080/LibreClinica/}.</li>
 * </ol>
 *
 * <pre>{@code
 * docker compose up -d
 * mvn -pl web -P smoke-tests test
 * }</pre>
 *
 * <h2>Why a separate profile (not integration-tests)</h2>
 *
 * The {@code integration-tests} profile runs against a postgres
 * service container in CI and exercises DAO + service paths headlessly.
 * Smoke tests require an actual app server + browser, which is not
 * currently wired into CI. Keeping the two profiles separate prevents
 * one CI red from blocking the other.
 *
 * <h2>Lifecycle</h2>
 *
 * One WebDriver per test method (cheap on a hot Chrome). Quit
 * unconditionally in {@link #tearDown} so a failing assertion doesn't
 * leak browser processes.
 */
public abstract class SmokeIT {

    /** System property: target base URL. Default points at the compose stack. */
    private static final String PROP_BASE_URL = "smoke.base.url";
    private static final String DEFAULT_BASE_URL = "http://localhost:8080/LibreClinica/";

    /** System property: run headless. Default true; set to false locally to watch the browser. */
    private static final String PROP_HEADLESS = "smoke.headless";

    /**
     * System property: optional URL of a Selenium WebDriver hub
     * (e.g. {@code http://localhost:4444/wd/hub} when running
     * {@code selenium/standalone-chrome} in Docker). When set, the
     * test connects via {@link RemoteWebDriver} instead of launching
     * a local Chrome. Useful when the test JVM has no browser binary
     * (CI containers, agent build environments).
     */
    private static final String PROP_REMOTE_URL = "webdriver.remote.url";

    /** Default credentials for the smoke sysadmin account — overridden by the install's first-run user. */
    protected static final String DEFAULT_USERNAME = "root";
    protected static final String DEFAULT_PASSWORD = "password";

    /** Reasonable global wait for page transitions and DataTables initialisation. */
    protected static final Duration WAIT = Duration.ofSeconds(15);

    protected WebDriver driver;
    protected WebDriverWait wait;
    protected String baseUrl;

    @Before
    public void startBrowser() throws Exception {
        baseUrl = System.getProperty(PROP_BASE_URL, DEFAULT_BASE_URL);
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }

        ChromeOptions options = new ChromeOptions();
        // selenium/standalone-chrome runs headless by default; --headless
        // is harmless when set on remote too. Default "true" means
        // headless. Set -Dsmoke.headless=false locally to watch the
        // browser visually (only meaningful when launching a local
        // ChromeDriver, not when going through the remote hub).
        if (!"false".equalsIgnoreCase(System.getProperty(PROP_HEADLESS, "true"))) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1280,1024");

        String remoteUrl = System.getProperty(PROP_REMOTE_URL);
        if (remoteUrl != null && !remoteUrl.isEmpty()) {
            driver = new RemoteWebDriver(URI.create(remoteUrl).toURL(), options);
        } else {
            driver = new ChromeDriver(options);
        }
        wait = new WebDriverWait(driver, WAIT);
    }

    @After
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    /**
     * On test failure, dump a screenshot + the current page source under
     * {@code target/smoke-failures/<testClassName>.<testMethod>/}. Makes
     * the difference between "timeout — no idea why" and "timeout — we
     * were stuck on the login error page because the seed credentials
     * are wrong". Files survive surefire teardown.
     */
    @Rule
    public TestWatcher failureArtifacts = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            if (driver == null) {
                return;
            }
            Path dir = Paths.get("target", "smoke-failures",
                    description.getClassName() + "." + description.getMethodName());
            try {
                Files.createDirectories(dir);
                try {
                    String pageSource = driver.getPageSource();
                    if (pageSource != null) {
                        Files.write(dir.resolve("page.html"),
                                pageSource.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception inner) {
                    // best-effort; don't mask the real failure
                }
                if (driver instanceof TakesScreenshot) {
                    byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                    Files.write(dir.resolve("screenshot.png"), png);
                }
                String url = driver.getCurrentUrl();
                if (url != null) {
                    Files.write(dir.resolve("current-url.txt"),
                            url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IOException ioe) {
                // best-effort; the real failure is what surefire reports
            }
        }
    };

    /**
     * Performs the standard form-based login. The login page lives at
     * {@code /MainMenu} on a fresh session; LibreClinica redirects there
     * on hitting any protected URL.
     */
    protected void loginAs(String username, String password) {
        driver.get(baseUrl + "MainMenu");
        // Login form fields are named j_username / j_password (Spring
        // Security 5 form-login default). Find by name to survive
        // visual restyling.
        WebElement user = driver.findElement(By.name("j_username"));
        WebElement pass = driver.findElement(By.name("j_password"));
        user.sendKeys(username);
        pass.sendKeys(password);
        pass.submit();
    }

    /**
     * Navigates to {@code path} relative to the base URL. {@code path}
     * may be a bare servlet name like {@code "AuditUserActivity"} or
     * include query parameters.
     */
    protected void goTo(String path) {
        driver.get(baseUrl + path);
    }
}
