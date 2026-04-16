package ca.siva.orchestrator.mock.actionregistry;

import ca.siva.orchestrator.actionregistry.ActionCodeEntry;
import ca.siva.orchestrator.actionregistry.DcxActionCodeEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * In-process mock of the PAM action-code registry APIs.
 *
 * <p>Serves two endpoints matching the real PAM ActionCodeService:</p>
 * <ul>
 *   <li>{@code GET /action-codes} → returns ActionCodeEntry list (actionName → actionCode)</li>
 *   <li>{@code GET /dcx-action-codes} → returns DcxActionCodeEntry list (parent/actionCode → dcxActionCode)</li>
 * </ul>
 *
 * <p>Lookup chain: actionName → actionCode (from /action-codes),
 * then actionCode = parent → dcxActionCode (from /dcx-action-codes).</p>
 */
@Slf4j
@RestController
@RequestMapping("/mock/actionregistry")
@Profile("local-dev")
public class MockActionRegistryController {

    /**
     * Returns actionName → actionCode mappings.
     * Mirrors the real PAM {@code GET /action-codes} endpoint.
     */
    @GetMapping("/action-codes")
    public List<ActionCodeEntry> actionCodes() {
        log.info("[MOCK action-registry] GET /action-codes");
        return List.of(
                new ActionCodeEntry("VOICE_SERVICE_DIAGNOSTIC", "runVoiceDiagnostic",
                        "Run voice service diagnostic", "Auto_Remediation", "action"),
                new ActionCodeEntry("INTERNET_CHECK", "runInternetCheck",
                        "Run internet connectivity check", "Auto_Remediation", "action"),
                new ActionCodeEntry("NOTIFY_USER", "sendNotification",
                        "Send notification to user", "Auto_Remediation", "action")
        );
    }

    /**
     * Returns actionCode (parent) → dcxActionCode mappings.
     * Mirrors the real PAM {@code GET /dcx-action-codes} endpoint.
     * The {@code parent} field is the actionCode — used as the join key.
     */
    @GetMapping("/dcx-action-codes")
    public List<DcxActionCodeEntry> dcxActionCodes() {
        log.info("[MOCK action-registry] GET /dcx-action-codes");
        return List.of(
                new DcxActionCodeEntry("VOICE_SERVICE_DIAGNOSTIC", "Auto_Remediation", "DCX-VSD-01", "action"),
                new DcxActionCodeEntry("INTERNET_CHECK", "Auto_Remediation", "DCX-INT-04", "action"),
                new DcxActionCodeEntry("NOTIFY_USER", "Auto_Remediation", "DCX-NOT-09", "action")
        );
    }
}
