package team.projectpulse.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserRepository;

/**
 * Turns a verified JWT into an authentication only while the account it names is still active
 * (FR-SEC-active-account).
 *
 * <p>The token's signature and expiry have already been checked when this runs. A token outlives the account's
 * state: a student or instructor deactivated after logging in would otherwise keep a working token until it
 * expires. So the account named by the {@code userId} claim is read on every request, and a deactivated or
 * deleted account is refused with {@link DisabledException}. Being an {@code AuthenticationException}, it goes
 * to the bearer-token entry point and becomes a 401, which the SPA answers by clearing the token and returning
 * to the login page. Authentication stays stateless: no token denylist, no session.
 *
 * <p>Cost: one existence query by primary key per authenticated request; no entity is loaded.
 */
public class ActiveAccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final Converter<Jwt, ? extends AbstractAuthenticationToken> delegate;

    private final UserRepository userRepository;


    public ActiveAccountJwtAuthenticationConverter(Converter<Jwt, ? extends AbstractAuthenticationToken> delegate, UserRepository userRepository) {
        this.delegate = delegate;
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Integer userId = readUserId(jwt);
        boolean active = userId != null && this.userRepository.existsByIdAndEnabledTrue(userId);
        if (!active) {
            throw new DisabledException("The account is deactivated or no longer exists.");
        }
        return this.delegate.convert(jwt);
    }

    private static Integer readUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim instanceof Number number) {
            return number.intValue();
        }
        if (claim instanceof String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

}
