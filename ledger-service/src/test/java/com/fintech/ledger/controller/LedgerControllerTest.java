package com.fintech.ledger.controller;

import com.fintech.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LedgerController.class)
class LedgerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LedgerService ledgerService;

    @Test
    void missingUserContextReturnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/ledger/my-entries"
                        )
                )
                .andExpect(
                        status()
                                .isUnauthorized()
                );

        verifyNoInteractions(
                ledgerService
        );
    }

    @Test
    void malformedUserContextReturnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/ledger/my-entries"
                        )
                                .header(
                                        "X-USER-ID",
                                        "not-a-uuid"
                                )
                )
                .andExpect(
                        status()
                                .isUnauthorized()
                );

        verifyNoInteractions(
                ledgerService
        );
    }

    @Test
    void normalUserCannotAccessAdminEndpoint()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/ledger/entries"
                        )
                                .header(
                                        "X-USER-ID",
                                        UUID.randomUUID()
                                                .toString()
                                )
                                .header(
                                        "X-USER-ROLES",
                                        "USER"
                                )
                )
                .andExpect(
                        status()
                                .isForbidden()
                );

        verifyNoInteractions(
                ledgerService
        );
    }

    @Test
    void adminCanAccessLedgerEntries()
            throws Exception {

        when(
                ledgerService
                        .getAllEntries()
        ).thenReturn(
                List.of()
        );

        mockMvc.perform(
                        get(
                                "/api/ledger/entries"
                        )
                                .header(
                                        "X-USER-ID",
                                        UUID.randomUUID()
                                                .toString()
                                )
                                .header(
                                        "X-USER-ROLES",
                                        "ADMIN"
                                )
                )
                .andExpect(
                        status().isOk()
                );

        verify(
                ledgerService
        ).getAllEntries();
    }
}