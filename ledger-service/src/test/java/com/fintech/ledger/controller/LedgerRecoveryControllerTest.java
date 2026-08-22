package com.fintech.ledger.controller;

import com.fintech.ledger.dto.LedgerRecoveryResponse;
import com.fintech.ledger.dto.LedgerRecoveryState;
import com.fintech.ledger.service.LedgerRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        LedgerRecoveryController.class
)
@TestPropertySource(
        properties = {
                "ledger.recovery.internal-key=test-recovery-key"
        }
)
class LedgerRecoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LedgerRecoveryService
            ledgerRecoveryService;

    @Test
    void missingInternalKeyIsRejected()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/internal/ledger/payments/{paymentId}",
                                UUID.randomUUID()
                        )
                )
                .andExpect(
                        status()
                                .isUnauthorized()
                );

        verifyNoInteractions(
                ledgerRecoveryService
        );
    }

    @Test
    void wrongInternalKeyIsRejected()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/internal/ledger/payments/{paymentId}",
                                UUID.randomUUID()
                        )
                                .header(
                                        "X-INTERNAL-RECOVERY-KEY",
                                        "wrong-key"
                                )
                )
                .andExpect(
                        status()
                                .isUnauthorized()
                );

        verifyNoInteractions(
                ledgerRecoveryService
        );
    }

    @Test
    void validInternalKeyReturnsLedgerState()
            throws Exception {

        UUID paymentId =
                UUID.randomUUID();

        when(
                ledgerRecoveryService
                        .lookup(paymentId)
        ).thenReturn(
                new LedgerRecoveryResponse(
                        paymentId,
                        LedgerRecoveryState.PROCESSED,
                        UUID.randomUUID(),
                        null,
                        null
                )
        );

        mockMvc.perform(
                        get(
                                "/internal/ledger/payments/{paymentId}",
                                paymentId
                        )
                                .header(
                                        "X-INTERNAL-RECOVERY-KEY",
                                        "test-recovery-key"
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.paymentId")
                                .value(
                                        paymentId.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.state")
                                .value(
                                        "PROCESSED"
                                )
                );
    }
}