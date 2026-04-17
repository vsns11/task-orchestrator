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
 * In-process mock of the PAM action-code registry APIs — response shapes
 * mirror the production endpoints:
 *
 * <ul>
 *   <li>{@code GET /availableActions/findAllAvailableActionsFromDb}
 *       (this mock maps it to {@code /action-codes})</li>
 *   <li>{@code GET /dcxActionCodes/findAllDcxActionCodesFromDb}
 *       (this mock maps it to {@code /dcx-action-codes})</li>
 * </ul>
 *
 * <p>Lookup chain (applied by {@link ca.siva.orchestrator.actionregistry.ActionRegistry}):
 * actionName → actionCode (via /action-codes), then actionCode = parent →
 * dcxActionCode (via /dcx-action-codes).</p>
 */
@Slf4j
@RestController
@RequestMapping("/mock/actionregistry")
@Profile("local-dev")
public class MockActionRegistryController {

    /** Matches the production shape: actionCode / name / enabled / actionType / description / createdTimeStamp. */
    @GetMapping("/action-codes")
    public List<ActionCodeEntry> actionCodes() {
        log.info("[MOCK action-registry] GET /action-codes");
        String now = "2025-04-08T21:07:51.883707707Z";
        return List.of(
                // Auto_Remediation DAG — multi-batch mixed sync/async
                new ActionCodeEntry("VOICE_SERVICE_DIAGNOSTIC", "runVoiceDiagnostic",
                        true, "MS", "Run voice service diagnostic", now),
                new ActionCodeEntry("INTERNET_CHECK",           "runInternetCheck",
                        true, "MS", "Run internet connectivity check", now),
                new ActionCodeEntry("NOTIFY_USER",              "sendNotification",
                        true, "MS", "Send notification to user", now),

                // passwordPushV2 DAG — single async action
                new ActionCodeEntry("PASSWORD_PUSH_V2",  "passwordPushV2",
                        true, "MS", "Push a new password to the target credential store", now),

                // passwordResetV2 DAG — single async action
                new ActionCodeEntry("PASSWORD_RESET_V2", "passwordResetV2",
                        true, "MS", "Reset a user's password in the target credential store", now),

                // miscellaneous DAG — single sync action (reference for plain SYNC flows)
                new ActionCodeEntry("MISCELLANEOUS",     "miscellaneous",
                        true, "MS", "Generic synchronous action used for sample/demo SYNC flows", now)
        );
    }

    /** Matches the production shape: parent / dcxActionCode / modemType / flowType. */
    @GetMapping("/dcx-action-codes")
    public List<DcxActionCodeEntry> dcxActionCodes() {
        log.info("[MOCK action-registry] GET /dcx-action-codes");
        return List.of(
                new DcxActionCodeEntry("VOICE_SERVICE_DIAGNOSTIC", "DCX-VSD-01",  "DEFAULT", "DEFAULT"),
                new DcxActionCodeEntry("INTERNET_CHECK",           "DCX-INT-04",  "DEFAULT", "DEFAULT"),
                new DcxActionCodeEntry("NOTIFY_USER",              "DCX-NOT-09",  "DEFAULT", "DEFAULT"),

                new DcxActionCodeEntry("PASSWORD_PUSH_V2",  "DCX-PWP-01",  "DEFAULT", "DEFAULT"),
                new DcxActionCodeEntry("PASSWORD_RESET_V2", "DCX-PWR-01",  "DEFAULT", "DEFAULT"),
                new DcxActionCodeEntry("MISCELLANEOUS",     "DCX-MISC-01", "DEFAULT", "DEFAULT")
        );
    }
}
