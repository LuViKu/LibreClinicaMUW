/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.core;

import java.util.Random;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SecurityManager
 *
 * <p>Phase D.1 (DR-015): the {@code encoder} property is now a
 * {@link org.springframework.security.crypto.password.DelegatingPasswordEncoder}
 * from {@link at.ac.meduniwien.ophthalmology.libreclinica.config.PasswordEncoderConfig}
 * — {@link #encryptPassword} produces {@code {bcrypt}…} hashes for ALL
 * new writes regardless of {@code isSoapUser}. The legacy SOAP branch
 * (unsalted hex SHA-1 via the deleted {@code OpenClinicaPasswordEncoder.soapEncode})
 * is removed because Spring WS was retired in Phase B.4 (PR #31) — the
 * {@code isSoapUser} parameter is preserved on the method signature
 * for caller-site compatibility but now has no behavioural effect.
 *
 * @author Krikor Krumlian
 */
public class SecurityManager {

    private PasswordEncoder encoder;

    private AuthenticationProvider providers[];

    /**
     * Generates a random password with default length
     */
    public String genPassword() {
        return genPassword(8);
    }

    /**
     * Generates a random password by length
     *
     * @param howMany how many characters
     */
    public String genPassword(int howMany) {
        StringBuilder password = new StringBuilder();
        String core = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rand = new Random();

        for (int i = 0; i < howMany; i++) {
            int index = rand.nextInt(core.length());
            char oneCharacter = core.charAt(index);
            password.append(oneCharacter);
        }

        return password.toString();
    }

    /**
     * Hashes the given plaintext via the wired
     * {@link PasswordEncoder} (post D.1 = bcrypt cost 10 via
     * {@code DelegatingPasswordEncoder}; output carries the
     * {@code {bcrypt}} prefix).
     *
     * @param password   the raw password to hash
     * @param isSoapUser preserved for caller-site compatibility; no
     *     effect post D.1. Pre-D.1 code took a different branch for
     *     SOAP users (unsalted hex SHA-1); Spring WS was retired in
     *     Phase B.4 (PR #31), removing the only consumer of that
     *     format.
     * @return the encoded password (D.1 onwards: {@code {bcrypt}…})
     */
    public String encryptPassword(String password, boolean isSoapUser) {
        return encoder.encode(password);
    }

    public boolean verifyPassword(String clearTextPassword, UserDetails userDetails) {

        Authentication authentication = new UsernamePasswordAuthenticationToken(
            userDetails.getUsername(),
            clearTextPassword
        );

        for (AuthenticationProvider p : providers) {
            try {
                p.authenticate(authentication);
                return true;
            } catch (AuthenticationException e) {
                // Nothing to do
            }
        }

        return false;
    }

    public PasswordEncoder getEncoder() {
        return encoder;
    }

    public void setEncoder(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public AuthenticationProvider[] getProviders() {
        return providers;
    }

    public void setProviders(AuthenticationProvider[] providers) {
        this.providers = providers;
    }

}