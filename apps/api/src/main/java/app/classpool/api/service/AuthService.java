package app.classpool.api.service;

import app.classpool.api.domain.AppUser;
import app.classpool.api.domain.AuthProvider;
import app.classpool.api.domain.Household;
import app.classpool.api.domain.Membership;
import app.classpool.api.dto.CurrentUserResponse;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.service.email.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final HouseholdRepository householdRepository;
    private final MembershipRepository membershipRepository;
    private final MembershipAssembler membershipAssembler;
    private final MagicLinkService magicLinkService;
    private final SessionService sessionService;
    private final EmailSender emailSender;
    private final String magicLinkBaseUrl;

    public AuthService(AppUserRepository appUserRepository,
                        HouseholdRepository householdRepository,
                        MembershipRepository membershipRepository,
                        MembershipAssembler membershipAssembler,
                        MagicLinkService magicLinkService,
                        SessionService sessionService,
                        EmailSender emailSender,
                        @Value("${classpool.magic-link.base-url}") String magicLinkBaseUrl) {
        this.appUserRepository = appUserRepository;
        this.householdRepository = householdRepository;
        this.membershipRepository = membershipRepository;
        this.membershipAssembler = membershipAssembler;
        this.magicLinkService = magicLinkService;
        this.sessionService = sessionService;
        this.emailSender = emailSender;
        this.magicLinkBaseUrl = magicLinkBaseUrl;
    }

    /** Always succeeds from the caller's point of view — no account enumeration (contract §requestMagicLink). */
    public void requestMagicLink(String email) {
        String normalized = email.trim().toLowerCase();
        String token = magicLinkService.issue(normalized);
        String link = magicLinkBaseUrl + "/api/v1/auth/magic-link/verify?token=" + token;
        emailSender.send(normalized, "Your ClassPool sign-in link",
                "Tap to sign in (expires in 15 minutes): " + link);
    }

    @Transactional
    public SessionService.Session verifyMagicLink(String token) {
        String email = magicLinkService.consume(token)
                .orElseThrow(() -> new BadRequestException("Token invalid, expired, or already used"));
        AppUser user = findOrCreateByEmail(email, AuthProvider.MAGIC_LINK, null);
        return sessionService.create(user.getId());
    }

    @Transactional
    public SessionService.Session establishGoogleSession(String googleSub, String email, String displayName) {
        AppUser user = appUserRepository.findByAuthProviderAndAuthProviderSub(AuthProvider.GOOGLE, googleSub)
                .orElseGet(() -> findOrCreateByEmail(email, AuthProvider.GOOGLE, googleSub, displayName));
        return sessionService.create(user.getId());
    }

    private AppUser findOrCreateByEmail(String email, AuthProvider provider, String providerSub) {
        return findOrCreateByEmail(email, provider, providerSub, defaultDisplayName(email));
    }

    private AppUser findOrCreateByEmail(String email, AuthProvider provider, String providerSub, String displayName) {
        String normalized = email.trim().toLowerCase();
        return appUserRepository.findByEmail(normalized)
                .orElseGet(() -> appUserRepository.save(new AppUser(normalized, displayName, provider, providerSub)));
    }

    private static String defaultDisplayName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    public void logout(String sessionToken) {
        sessionService.invalidate(sessionToken);
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Session user no longer exists"));
        UUID householdId = householdRepository.findByPrimaryParentId(userId).map(Household::getId).orElse(null);
        List<Membership> memberships = membershipRepository.findByParentUserIdOrderByCreatedAtAsc(userId);
        List<MembershipResponse> membershipResponses = membershipAssembler.toResponses(memberships);
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getDisplayName(), householdId,
                membershipResponses);
    }
}
