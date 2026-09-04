package app.classpool.api.service;

import app.classpool.api.domain.AppUser;
import app.classpool.api.domain.AuthProvider;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.service.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private HouseholdRepository householdRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private MembershipAssembler membershipAssembler;
    @Mock
    private MagicLinkService magicLinkService;
    @Mock
    private SessionService sessionService;
    @Mock
    private EmailSender emailSender;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(appUserRepository, householdRepository, membershipRepository,
                membershipAssembler, magicLinkService, sessionService, emailSender, "http://localhost:8080");
    }

    @Test
    void requestMagicLink_neverThrows_andAlwaysSendsAnEmail() {
        when(magicLinkService.issue("parent@example.com")).thenReturn("tok-123");

        authService.requestMagicLink("parent@example.com");

        verify(emailSender).send(eq("parent@example.com"), anyString(), contains("tok-123"));
    }

    @Test
    void verifyMagicLink_rejectsAnInvalidOrConsumedToken() {
        when(magicLinkService.consume("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyMagicLink("bad-token"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(sessionService);
    }

    @Test
    void verifyMagicLink_createsANewUser_onFirstSignIn() {
        when(magicLinkService.consume("good-token")).thenReturn(Optional.of("newuser@example.com"));
        when(appUserRepository.findByEmail("newuser@example.com")).thenReturn(Optional.empty());
        UUID sessionUserId = UUID.randomUUID();
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser saved = inv.getArgument(0);
            setId(saved, sessionUserId);
            return saved;
        });
        when(sessionService.create(sessionUserId))
                .thenReturn(new SessionService.Session("session-token", sessionUserId, Instant.now()));

        SessionService.Session session = authService.verifyMagicLink("good-token");

        assertThat(session.token()).isEqualTo("session-token");
        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("newuser@example.com");
        assertThat(userCaptor.getValue().getAuthProvider()).isEqualTo(AuthProvider.MAGIC_LINK);
    }

    @Test
    void verifyMagicLink_reusesTheExistingUser_onRepeatSignIn() {
        AppUser existing = new AppUser("returning@example.com", "Returning", AuthProvider.MAGIC_LINK, null);
        setId(existing, UUID.randomUUID());

        when(magicLinkService.consume("returning-token")).thenReturn(Optional.of("returning@example.com"));
        when(appUserRepository.findByEmail("returning@example.com")).thenReturn(Optional.of(existing));
        when(sessionService.create(existing.getId()))
                .thenReturn(new SessionService.Session("tok", existing.getId(), Instant.now()));

        authService.verifyMagicLink("returning-token");

        verify(appUserRepository, never()).save(any());
        verify(sessionService).create(existing.getId());
    }

    private static void setId(AppUser user, UUID id) {
        try {
            var field = AppUser.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
