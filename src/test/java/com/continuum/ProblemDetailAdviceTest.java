package com.continuum;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Vérifie que {@link ProblemDetailAdvice} rend les erreurs en {@code application/problem+json} (RFC 9457,
 * contrat D32) avec {@code title}/{@code status}/{@code traceId} — y compris le {@code detail} des
 * {@code ResponseStatusException} des contrôleurs et le filet de sécurité 500. MockMvc autonome : ni
 * contexte Spring ni base.
 */
class ProblemDetailAdviceTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new ProblemDetailAdvice())
                .build();
    }

    @Test
    void responseStatusExceptionBecomesProblemJsonWithTraceId() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/boom-conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("SoD : validation par une autre personne requise"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unexpectedExceptionBecomesProblemJson500WithTraceId() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/boom-unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Erreur interne"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @RestController
    static class BoomController {
        @GetMapping("/boom-conflict")
        void conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SoD : validation par une autre personne requise");
        }

        @GetMapping("/boom-unexpected")
        void unexpected() {
            throw new IllegalStateException("panne interne non prévue");
        }
    }
}
