package dev.sluice.auth;

import dev.sluice.account.Account;
import dev.sluice.account.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Virtual keys arrive exactly where a provider key would, so pointing an SDK at
 * Sluice is a base-URL change and nothing else.
 */
@Component
public class PrincipalResolver {

    private final VirtualKeyService keys;
    private final AccountService accounts;

    public PrincipalResolver(VirtualKeyService keys, AccountService accounts) {
        this.keys = keys;
        this.accounts = accounts;
    }

    public Principal resolve(HttpServletRequest request) {
        String presented = request.getHeader("x-api-key");
        if (presented == null) {
            String authorization = request.getHeader("authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "bearer ", 0, 7)) {
                presented = authorization.substring(7).trim();
            }
        }
        VirtualKey key = keys.resolve(presented)
                .orElseThrow(() -> new UnauthorizedException("unknown or revoked virtual key"));
        List<Account> chain = accounts.chain(key.accountId());
        return new Principal(key, chain.get(0), chain);
    }
}
