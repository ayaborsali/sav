package tn.higer.sav_backend.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.UserSession;

import java.time.Instant;
import java.time.ZoneId;

@Service
public class JwtService {

    private final JwtEncoder encoder;

    public JwtService(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    /** Fabrique le jeton. Il contient l'identifiant de la session (« sid »), pas le rôle. */
    public String generate(AppUser user, UserSession session) {
        Instant expiresAt = session.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("sav-backend")
                .issuedAt(Instant.now())
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("sid", session.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}