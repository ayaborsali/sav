package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.controller.MissionGroupAttachmentController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MissionGroupChecklistImportServiceTest {
    private final Authentication atelier = auth("CHEF_ATELIER");
    private final MockMultipartFile pdf = new MockMultipartFile("file", "fiche.pdf", "application/pdf",
            "%PDF-1.4\ncontenu".getBytes(StandardCharsets.UTF_8));

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("utilisateur", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private MissionGroupChecklistImportService service() {
        return spy(new MissionGroupChecklistImportService(10_000_000, "python", "scripts/read_workshop_pdf.py"));
    }

    private static Process completed() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.descendants()).thenAnswer(invocation -> Stream.empty());
        return process;
    }

    private static void envelope(MissionGroupChecklistImportService service, String json) throws Exception {
        Process process = completed();
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(1, Path.class), json, StandardCharsets.UTF_8);
            return process;
        }).when(service).startProcess(any(Path.class), any(Path.class));
    }

    @Test
    void springChoisitLeConstructeurConfigure() {
        try (var context = new AnnotationConfigApplicationContext(MissionGroupChecklistImportService.class)) {
            assertNotNull(context.getBean(MissionGroupChecklistImportService.class));
        }
    }

    @Test
    void leTechnicienEtLaConsultationNePeuventPasImporter() throws Exception {
        var service = service();
        for (String role : List.of("TECHNICIEN", "CONSULTATION")) {
            var error = assertThrows(ResponseStatusException.class, () -> service.preview(pdf, auth(role)));
            assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        }
        verify(service, never()).startProcess(any(), any());
    }

    @Test
    void valideTypeTailleEtSignatureAvantLecture() throws Exception {
        var service = service();
        for (MockMultipartFile invalid : List.of(
                new MockMultipartFile("file", "vide.pdf", "application/pdf", new byte[0]),
                new MockMultipartFile("file", "fiche.txt", "text/plain", "%PDF-texte".getBytes()),
                new MockMultipartFile("file", "faux.pdf", "application/pdf", "pas un PDF".getBytes()))) {
            var error = assertThrows(ResponseStatusException.class, () -> service.preview(invalid, atelier));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        var limited = spy(new MissionGroupChecklistImportService(5, "python", "unused"));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,
                assertThrows(ResponseStatusException.class, () -> limited.preview(pdf, atelier)).getStatusCode());
        verify(service, never()).startProcess(any(), any());
        verify(limited, never()).startProcess(any(), any());
    }

    @Test
    void conserveLeTexteEtSupprimeLesFichiersTemporaires() throws Exception {
        var service = service();
        AtomicReference<Path> source = new AtomicReference<>();
        AtomicReference<Path> output = new AtomicReference<>();
        Process process = completed();
        doAnswer(invocation -> {
            source.set(invocation.getArgument(0));
            output.set(invocation.getArgument(1));
            assertEquals(new String(pdf.getBytes(), StandardCharsets.UTF_8), Files.readString(source.get()));
            Files.writeString(output.get(), "{\"ok\":true,\"tasks\":[\"Vérifier l’huile\",\"Rechercher les fuites\"],\"pageCount\":2}");
            return process;
        }).when(service).startProcess(any(), any());
        var preview = service.preview(pdf, atelier);
        assertEquals(List.of("Vérifier l’huile", "Rechercher les fuites"), preview.tasks());
        assertEquals(2, preview.pageCount());
        assertFalse(Files.exists(source.get()));
        assertFalse(Files.exists(output.get()));
    }

    @Test
    void refuseLeScanEtLesSortiesNonConformesSansTachesInventees() throws Exception {
        var service = service();
        envelope(service, "{\"ok\":false,\"kind\":\"no_text\",\"error\":\"private details\"}");
        var scan = assertThrows(ResponseStatusException.class, () -> service.preview(pdf, atelier));
        assertEquals(HttpStatus.BAD_REQUEST, scan.getStatusCode());
        assertTrue(scan.getReason().contains("scannée"));
        assertFalse(scan.getReason().contains("private"));
        for (String json : List.of("invalid", "{}", "{\"ok\":true,\"tasks\":[],\"pageCount\":1}",
                "{\"ok\":true,\"tasks\":[42],\"pageCount\":1}",
                "{\"ok\":true,\"tasks\":[\"tâche\"],\"pageCount\":21}",
                "{\"ok\":true,\"tasks\":[\"" + "x".repeat(301) + "\"],\"pageCount\":1}")) {
            envelope(service, json);
            assertThrows(ResponseStatusException.class, () -> service.preview(pdf, atelier));
        }
    }

    @Test
    void borneLaSortieDuProcessus() throws Exception {
        var service = service();
        envelope(service, "x".repeat(256 * 1024 + 1));
        assertEquals(HttpStatus.BAD_GATEWAY,
                assertThrows(ResponseStatusException.class, () -> service.preview(pdf, atelier)).getStatusCode());
    }

    @Test
    void leDelaiEstBorneEtLeProcessusArrete() throws Exception {
        var service = spy(new MissionGroupChecklistImportService(10_000_000, "python", "unused", 5));
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenAnswer(invocation -> Stream.empty());
        doReturn(process).when(service).startProcess(any(), any());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT,
                assertThrows(ResponseStatusException.class, () -> service.preview(pdf, atelier)).getStatusCode());
        verify(process).destroyForcibly();
    }

    @Test
    void pythonAbsentEstSignaleSansDetailsInternes() throws Exception {
        var service = service();
        doThrow(new IOException("private path")).when(service).startProcess(any(), any());
        var error = assertThrows(ResponseStatusException.class, () -> service.preview(pdf, atelier));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertFalse(error.getReason().contains("private"));
    }

    @Test
    void leControleurExposeExactementTasksEtPageCount() throws Exception {
        var service = service();
        envelope(service, "{\"ok\":true,\"tasks\":[\"Vérifier l’huile\"],\"pageCount\":1}");
        var mvc = MockMvcBuilders.standaloneSetup(new MissionGroupAttachmentController(
                mock(MissionGroupAttachmentService.class), service)).build();
        mvc.perform(multipart("/api/missions-groupe/fiche-atelier/apercu").file(pdf).principal(atelier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0]").value("Vérifier l’huile"))
                .andExpect(jsonPath("$.pageCount").value(1));
        mvc.perform(multipart("/api/missions-groupe/fiche-atelier/apercu").file(pdf).principal(auth("TECHNICIEN")))
                .andExpect(status().isForbidden());
    }
}
