package com.zenix.repeatzero;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.zenix.repeatzero.data.PatternEngine;
import com.zenix.repeatzero.data.RepeatZeroDatabase;
import com.zenix.repeatzero.model.FlowStep;
import com.zenix.repeatzero.model.ObservedEvent;
import com.zenix.repeatzero.model.PatternSuggestion;
import com.zenix.repeatzero.model.Route;
import com.zenix.repeatzero.service.RepeatZeroAccessibilityService;
import com.zenix.repeatzero.test.TargetActivity;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class RepeatZeroInstrumentedTest {
    private static final String APP = "com.zenix.repeatzero";
    private static final String TEST_APP = "com.zenix.repeatzero.test";
    private static final String SERVICE = APP + "/com.zenix.repeatzero.service.RepeatZeroAccessibilityService";

    private Context appContext;
    private Context testContext;
    private UiDevice device;

    @Before
    public void setup() {
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testContext = InstrumentationRegistry.getInstrumentation().getContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test
    public void a_modelsPrivacyAndSensitiveGuard() throws Exception {
        List<FlowStep> steps = Arrays.asList(
                new FlowStep(TEST_APP, TEST_APP + ":id/btn_a", "android.widget.Button"),
                new FlowStep(TEST_APP, TEST_APP + ":id/btn_b", "android.widget.Button")
        );
        Route route = new Route();
        route.steps.addAll(steps);
        List<FlowStep> decoded = Route.stepsFromJson(route.stepsToJson());
        assertEquals(2, decoded.size());
        assertEquals(steps.get(0).viewId, decoded.get(0).viewId);
        assertTrue(Route.stepsFromJson("not json").isEmpty());

        PackageInfo pi = appContext.getPackageManager().getPackageInfo(APP, PackageManager.GET_PERMISSIONS);
        List<String> permissions = pi.requestedPermissions == null
                ? new ArrayList<>() : Arrays.asList(pi.requestedPermissions);
        assertFalse("RepeatZero must not request INTERNET", permissions.contains(Manifest.permission.INTERNET));

        ApplicationInfo ai = appContext.getPackageManager().getApplicationInfo(APP, 0);
        assertEquals("Backups must be disabled", 0, ai.flags & ApplicationInfo.FLAG_ALLOW_BACKUP);

        Method sensitive = RepeatZeroAccessibilityService.class.getDeclaredMethod("isSensitiveStep", String.class);
        sensitive.setAccessible(true);
        RepeatZeroAccessibilityService service = new RepeatZeroAccessibilityService();
        assertTrue((Boolean) sensitive.invoke(service, TEST_APP + ":id/payment_button"));
        assertTrue((Boolean) sensitive.invoke(service, TEST_APP + ":id/confirm_transfer"));
        assertTrue((Boolean) sensitive.invoke(service, TEST_APP + ":id/delete_account"));
        assertFalse((Boolean) sensitive.invoke(service, TEST_APP + ":id/btn_a"));
    }

    @Test
    public void b_databaseCrudRetentionAndDuplicateRoute() {
        RepeatZeroDatabase db = RepeatZeroDatabase.get(appContext);
        clearDb(db);

        long old = System.currentTimeMillis() - (15L * 24L * 60L * 60L * 1000L);
        db.addEvent(new ObservedEvent("old", old, TEST_APP,
                AccessibilityEvent.TYPE_VIEW_CLICKED, TEST_APP + ":id/btn_a", "Button", "Target"));
        assertEquals("Events older than 14 days must be pruned", 0, db.getEventCount());

        db.addEvent(new ObservedEvent("now", System.currentTimeMillis(), TEST_APP,
                AccessibilityEvent.TYPE_VIEW_CLICKED, TEST_APP + ":id/btn_a", "Button", "Target"));
        assertEquals(1, db.getEventCount());
        assertEquals(1, db.getRecentEvents(10).size());

        PatternSuggestion suggestion = suggestion(
                "db-route",
                TEST_APP + ":id/btn_a",
                TEST_APP + ":id/btn_b",
                TEST_APP + ":id/btn_c"
        );
        long id = db.saveRoute(suggestion);
        assertTrue(id > 0);
        assertEquals("Duplicate fingerprint must be ignored", -1, db.saveRoute(suggestion));
        Route saved = db.getRoute(id);
        assertNotNull(saved);
        assertEquals(TEST_APP, saved.packageName);
        assertEquals(3, saved.steps.size());

        db.clearEvents();
        assertEquals(0, db.getEventCount());
        assertEquals("Clearing observations must preserve approved routes", 1, db.getRoutes().size());

        db.deleteRoute(id);
        assertNull(db.getRoute(id));
        assertTrue(db.getRoutes().isEmpty());
    }

    @Test
    public void c_patternEngineDetectsRepetitionAndFriction() {
        long base = System.currentTimeMillis();
        List<ObservedEvent> repeated = new ArrayList<>();
        String[] ids = {"btn_a", "btn_b", "btn_c"};
        for (int i = 0; i < 9; i++) {
            repeated.add(new ObservedEvent(
                    "session-repeat",
                    base + i * 1000L,
                    TEST_APP,
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                    TEST_APP + ":id/" + ids[i % 3],
                    "android.widget.Button",
                    "TargetActivity"
            ));
        }
        List<PatternSuggestion> found = PatternEngine.analyze(appContext, repeated);
        PatternSuggestion auto = null;
        for (PatternSuggestion p : found) {
            if (p.automatable && p.steps.size() == 3) {
                auto = p;
                break;
            }
        }
        assertNotNull("ABC repeated 3 times must be detected", auto);
        assertTrue(auto.occurrences >= 3);

        List<ObservedEvent> friction = Arrays.asList(
                win("friction", base, "ScreenA"),
                win("friction", base + 1000, "ScreenB"),
                win("friction", base + 2000, "ScreenA"),
                win("friction", base + 3000, "ScreenB"),
                win("friction", base + 4000, "ScreenA")
        );
        List<PatternSuggestion> frictionFound = PatternEngine.analyze(appContext, friction);
        boolean hasFriction = false;
        for (PatternSuggestion p : frictionFound) {
            if (!p.automatable && "Fricción detectada".equals(p.title) && p.occurrences >= 2) {
                hasFriction = true;
            }
        }
        assertTrue("A-B-A repeated bounce must be detected as friction", hasFriction);
    }

    @Test
    public void z_endToEndUserFacingFunctions() throws Exception {
        RepeatZeroDatabase db = RepeatZeroDatabase.get(appContext);
        clearDb(db);
        appContext.getSharedPreferences("repeatzero_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        resetTarget(false);

        // 1) Disclosure before Accessibility activation.
        disableService();
        startMain();
        assertText("Desactivada", 4000);
        clickRes(APP, "enableServiceButton");
        assertText("Antes de activar RepeatZero", 4000);
        assertTextContains("No guarda lo que escribís", 4000);
        clickText("Cancelar");
        assertFalse(appContext.getSharedPreferences("repeatzero_prefs", Context.MODE_PRIVATE)
                .getBoolean("accessibility_disclosure_accepted", false));

        // 2) Enable service and verify state.
        enableService();
        startMain();
        assertText("Activa", 7000);

        // 3) Light/dark system theme survives recreation.
        device.executeShellCommand("cmd uimode night yes");
        SystemClock.sleep(800);
        startMain();
        assertNotNull(findRes(APP, "statusPill", 4000));
        device.executeShellCommand("cmd uimode night no");
        SystemClock.sleep(800);
        startMain();
        assertNotNull(findRes(APP, "statusPill", 4000));

        // 4) Pause blocks observation; resume restores it.
        db.clearEvents();
        clickRes(APP, "pauseButton");
        assertText("Pausada", 3000);
        startTarget();
        clickRes(TEST_APP, "btn_a");
        SystemClock.sleep(900);
        assertFalse(hasClickEvent(db, "btn_a"));

        startMain();
        clickRes(APP, "pauseButton");
        assertText("Activa", 3000);
        db.clearEvents();
        startTarget();
        clickRes(TEST_APP, "btn_a");
        SystemClock.sleep(1000);
        assertTrue("Click metadata must be captured after resume", hasClickEvent(db, "btn_a"));

        // 5) Password field is ignored while normal controls are observed.
        db.clearEvents();
        startTarget();
        UiObject2 password = findRes(TEST_APP, "password_field", 3000);
        assertNotNull(password);
        password.click();
        password.setText("secret123");
        clickRes(TEST_APP, "btn_a");
        SystemClock.sleep(1000);
        assertFalse("Password control must never be stored", hasAnyViewId(db, "password_field"));
        assertTrue("Normal click must still be stored", hasClickEvent(db, "btn_a"));

        // 6) Observe ABC x3, detect it, approve Route through UI.
        db.clearEvents();
        clearRoutes(db);
        resetTarget(false);
        startTarget();
        for (int i = 0; i < 3; i++) {
            clickRes(TEST_APP, "btn_a");
            clickRes(TEST_APP, "btn_b");
            clickRes(TEST_APP, "btn_c");
        }
        SystemClock.sleep(1400);
        assertTrue(db.getEventCount() >= 9);

        startMain();
        scrollTop();
        clickRes(APP, "analyzeButton");
        assertText("Rutina repetida en RZ Test Target", 7000);
        clickResScrollDown(APP, "createRouteButton");
        assertText("Crear esta Ruta", 3000);
        clickText("Crear Ruta");
        waitForRouteCount(db, 1, 5000);
        assertEquals(1, db.getRoutes().size());

        // 7) Clear observations from UI, keeping approved Route.
        clickResScrollDown(APP, "clearHistoryButton");
        assertText("Borrar observaciones", 3000);
        clickText("Borrar");
        waitForEventCount(db, 0, 5000);
        assertEquals(1, db.getRoutes().size());

        // 8) Execute Route: exactly 3 deterministic clicks.
        startMain();
        UiObject2 run = findResScrollDown(APP, "runRouteButton");
        assertNotNull(run);
        run.click();
        assertTextContains("RepeatZero abrirá la app", 3000);
        clickText("Ejecutar");
        assertTextContains("Detener", 3500);
        assertTargetCount(12, 7000);
        waitUntilNoTextContains("Detener", 4000);

        // 9) Delete Route through UI.
        startMain();
        UiObject2 delete = findResScrollDown(APP, "deleteRouteButton");
        assertNotNull(delete);
        delete.click();
        assertText("Eliminar Ruta", 3000);
        clickText("Eliminar");
        waitForRouteCount(db, 0, 5000);

        // 10) Sensitive payment step is blocked before click.
        clearRoutes(db);
        resetTarget(false);
        long sensitiveId = db.saveRoute(suggestion(
                "sensitive-route",
                TEST_APP + ":id/btn_a",
                TEST_APP + ":id/payment_button",
                TEST_APP + ":id/btn_c"
        ));
        assertTrue(sensitiveId > 0);
        runOnlyRouteFromMain();
        assertTargetCount(1, 5000);
        SystemClock.sleep(1800);
        assertEquals(1, targetCount());
        waitUntilNoTextContains("Detener", 3000);

        // 11) Changed interface stops Route instead of guessing.
        clearRoutes(db);
        resetTarget(true);
        long mismatchId = db.saveRoute(suggestion(
                "mismatch-route",
                TEST_APP + ":id/btn_a",
                TEST_APP + ":id/btn_b",
                TEST_APP + ":id/btn_c"
        ));
        assertTrue(mismatchId > 0);
        runOnlyRouteFromMain();
        assertTargetCount(1, 4500);
        SystemClock.sleep(7000);
        assertEquals("C must not be guessed/clicked after missing B", 1, targetCount());
        waitUntilNoTextContains("Detener", 2500);

        // 12) Floating Stop cancels an in-progress Route.
        clearRoutes(db);
        resetTarget(false);
        PatternSuggestion stuck = new PatternSuggestion(
                "manual-stop",
                "Rutina repetida en RZ Test Target",
                "test",
                "test",
                TEST_APP,
                3,
                2,
                true,
                Arrays.asList(new FlowStep(TEST_APP, TEST_APP + ":id/missing_button", "android.widget.Button"))
        );
        assertTrue(db.saveRoute(stuck) > 0);
        runOnlyRouteFromMain();
        UiObject2 stop = waitTextContainsObject("Detener", 3500);
        assertNotNull("Floating stop overlay must be visible", stop);
        stop.click();
        waitUntilNoTextContains("Detener", 3000);
        SystemClock.sleep(1500);
        assertEquals(0, targetCount());

        clearDb(db);
        resetTarget(false);
        device.executeShellCommand("cmd uimode night no");
    }

    private ObservedEvent win(String session, long ts, String window) {
        return new ObservedEvent(session, ts, TEST_APP,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "", "", window);
    }

    private PatternSuggestion suggestion(String fingerprint, String... viewIds) {
        List<FlowStep> steps = new ArrayList<>();
        for (String id : viewIds) {
            steps.add(new FlowStep(TEST_APP, id, "android.widget.Button"));
        }
        return new PatternSuggestion(
                fingerprint,
                "Rutina repetida en RZ Test Target",
                "test",
                "test",
                TEST_APP,
                3,
                2,
                true,
                steps
        );
    }

    private void clearDb(RepeatZeroDatabase db) {
        db.clearEvents();
        clearRoutes(db);
    }

    private void clearRoutes(RepeatZeroDatabase db) {
        for (Route r : db.getRoutes()) db.deleteRoute(r.id);
    }

    private boolean hasClickEvent(RepeatZeroDatabase db, String idSuffix) {
        for (ObservedEvent e : db.getRecentEvents(200)) {
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                    && e.viewId != null && e.viewId.endsWith("/" + idSuffix)) return true;
        }
        return false;
    }

    private boolean hasAnyViewId(RepeatZeroDatabase db, String idSuffix) {
        for (ObservedEvent e : db.getRecentEvents(200)) {
            if (e.viewId != null && e.viewId.endsWith("/" + idSuffix)) return true;
        }
        return false;
    }

    private void enableService() throws Exception {
        device.executeShellCommand("settings put secure enabled_accessibility_services " + SERVICE);
        device.executeShellCommand("settings put secure accessibility_enabled 1");
        SystemClock.sleep(1800);
    }

    private void disableService() throws Exception {
        device.executeShellCommand("settings delete secure enabled_accessibility_services");
        device.executeShellCommand("settings put secure accessibility_enabled 0");
        SystemClock.sleep(1000);
    }

    private void startMain() throws Exception {
        device.pressHome();
        device.executeShellCommand("am start -W -n " + APP + "/.ui.MainActivity");
        assertNotNull(findRes(APP, "statusPill", 7000));
        device.waitForIdle();
    }

    private void startTarget() throws Exception {
        device.pressHome();
        device.executeShellCommand("am start -W -n " + TEST_APP + "/.TargetActivity");
        assertNotNull(findRes(TEST_APP, "counter", 7000));
        device.waitForIdle();
    }

    private void resetTarget(boolean hideB) {
        SharedPreferences p = testContext.getSharedPreferences(TargetActivity.PREFS, Context.MODE_PRIVATE);
        p.edit().putInt(TargetActivity.KEY_COUNT, 0)
                .putBoolean(TargetActivity.KEY_HIDE_B, hideB).commit();
    }

    private int targetCount() {
        return testContext.getSharedPreferences(TargetActivity.PREFS, Context.MODE_PRIVATE)
                .getInt(TargetActivity.KEY_COUNT, 0);
    }

    private void runOnlyRouteFromMain() throws Exception {
        startMain();
        UiObject2 run = findResScrollDown(APP, "runRouteButton");
        assertNotNull(run);
        run.click();
        assertTextContains("RepeatZero abrirá la app", 3000);
        clickText("Ejecutar");
        assertNotNull(waitTextContainsObject("Detener", 3500));
    }

    private void assertTargetCount(int expected, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            UiObject2 c = device.findObject(By.res(TEST_APP, "counter"));
            if (c != null && ("Count: " + expected).equals(c.getText())) return;
            SystemClock.sleep(150);
        }
        fail("Expected target Count: " + expected + ", actual prefs count=" + targetCount());
    }

    private void waitForRouteCount(RepeatZeroDatabase db, int expected, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            if (db.getRoutes().size() == expected) return;
            SystemClock.sleep(100);
        }
        fail("Expected route count " + expected + ", got " + db.getRoutes().size());
    }

    private void waitForEventCount(RepeatZeroDatabase db, int expected, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            if (db.getEventCount() == expected) return;
            SystemClock.sleep(100);
        }
        fail("Expected event count " + expected + ", got " + db.getEventCount());
    }

    private UiObject2 findRes(String pkg, String id, long timeout) {
        return device.wait(Until.findObject(By.res(pkg, id)), timeout);
    }

    private void clickRes(String pkg, String id) {
        UiObject2 o = findRes(pkg, id, 4000);
        assertNotNull("Missing " + pkg + ":id/" + id, o);
        o.click();
        SystemClock.sleep(220);
    }

    private UiObject2 findResScrollDown(String pkg, String id) {
        UiObject2 o = device.findObject(By.res(pkg, id));
        for (int i = 0; i < 9 && o == null; i++) {
            device.swipe(device.getDisplayWidth() / 2, device.getDisplayHeight() * 4 / 5,
                    device.getDisplayWidth() / 2, device.getDisplayHeight() / 5, 20);
            SystemClock.sleep(250);
            o = device.findObject(By.res(pkg, id));
        }
        return o;
    }

    private void clickResScrollDown(String pkg, String id) {
        UiObject2 o = findResScrollDown(pkg, id);
        assertNotNull("Missing after scroll " + pkg + ":id/" + id, o);
        o.click();
        SystemClock.sleep(250);
    }

    private void scrollTop() {
        for (int i = 0; i < 9; i++) {
            device.swipe(device.getDisplayWidth() / 2, device.getDisplayHeight() / 4,
                    device.getDisplayWidth() / 2, device.getDisplayHeight() * 4 / 5, 20);
        }
        SystemClock.sleep(300);
    }

    private void clickText(String text) {
        UiObject2 o = device.wait(Until.findObject(By.text(text)), 4000);
        assertNotNull("Missing text: " + text, o);
        o.click();
        SystemClock.sleep(250);
    }

    private void assertText(String text, long timeout) {
        assertNotNull("Missing text: " + text, device.wait(Until.findObject(By.text(text)), timeout));
    }

    private void assertTextContains(String text, long timeout) {
        assertNotNull("Missing text containing: " + text,
                device.wait(Until.findObject(By.textContains(text)), timeout));
    }

    private UiObject2 waitTextContainsObject(String text, long timeout) {
        return device.wait(Until.findObject(By.textContains(text)), timeout);
    }

    private void waitUntilNoTextContains(String text, long timeout) {
        boolean gone = device.wait(Until.gone(By.textContains(text)), timeout);
        assertTrue("Text should disappear: " + text, gone);
    }
}
