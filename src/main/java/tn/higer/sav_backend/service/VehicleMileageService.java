package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.MileageOrigin;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDateTime;

/**
 * LA PORTE DU COMPTEUR : tout ce qui écrit le kilométrage d'un bus passe ici.
 *
 * Le portail connaissait le même chiffre à deux endroits qui ne se parlaient pas :
 * la colonne « kilometrage » de l'écran Véhicules, tapée à la main, et les compteurs
 * relus par le technicien pendant une sortie (à l'arrivée, puis à la fin). Un bus à
 * 8 000 km sur sa fiche et relevé à 10 120 km le 18/10 donnait deux réponses selon
 * l'écran ouvert. Cette classe les réconcilie, dans un seul sens :
 *
 *  1. <b>un relevé fait monter la fiche.</b> Le technicien qui relit le compteur
 *     tient une mesure ; la fiche s'aligne, sans rien ressaisir à l'atelier ;
 *  2. <b>ça ne redescend pas.</b> Un compteur qui diminue signe une erreur, pas un
 *     voyage. Une saisie plus basse que ce que le portail sait déjà est refusée, et
 *     le refus nomme la sortie, celui qui l'a lue, la valeur et la date : un
 *     « kilométrage invalide » muet ne se corrige pas sur un poste de travail ;
 *  3. <b>la baisse se paie par écrit.</b> Un compteur remplacé, un relevé faux : ça
 *     existe, et ça se consigne dans {@code mileageCorrectionReason} avec une ligne
 *     d'audit. Seuls les comptes qui écrivent déjà une fiche de bus le peuvent —
 *     c'est {@code SecurityConfig} qui les admet sur PUT /api/vehicles, la règle est
 *     la même que pour lever un rappel de palier ;
 *  4. <b>la valeur dit d'où elle vient</b> : origine, heure et auteur posés sur la
 *     fiche du bus.
 *
 * La règle est posée à la porte et non dans un écran, parce qu'une troisième main
 * est annoncée : le capteur embarqué qui déterminera le kilométrage. Il arrivera
 * avec sa propre origine et la même règle, au lieu d'écrire en cachette dans un
 * troisième coin du code.
 */
@Service
public class VehicleMileageService {

    /** La borne commune des compteurs, celle de la sortie de dépannage comme celle de la fiche. */
    private static final long MAX_COMPTEUR = 999_999L;

    private final VehicleRepository vehicles;
    private final InterventionRepository interventions;
    private final TechnicianRepository technicians;
    private final AuditService audit;

    public VehicleMileageService(VehicleRepository vehicles, InterventionRepository interventions,
                                 TechnicianRepository technicians, AuditService audit) {
        this.vehicles = vehicles;
        this.interventions = interventions;
        this.technicians = technicians;
        this.audit = audit;
    }

    /** Le compteur le plus haut lu sur une sortie de ce bus, et ce qui l'a lu. */
    public record DernierReleve(long km, String sortie, String par, LocalDateTime quand) {}

    // ------------------------------------------------------------------ la porte

    /**
     * La saisie de l'atelier (écran Véhicules). Refuse une valeur plus basse que ce
     * que le portail sait déjà du bus tant qu'aucun motif n'est écrit.
     *
     * L'entité est modifiée ici, pas enregistrée : c'est l'appelant qui écrit —
     * {@code VehicleController} après son {@code apply}, ou la transaction du service
     * de dépannage pour un relevé.
     *
     * @param motif pourquoi le compteur est remis plus bas ; exigé seulement dans ce cas
     */
    @Transactional
    public void saisieAtelier(Vehicle bus, Long valeur, String motif, Jwt jwt) {
        if (valeur == null) {
            // Rien de tapé : la fiche garde ce qu'elle sait. Un compteur ne se remet
            // pas à zéro parce qu'un champ est resté vide dans un formulaire.
            if (bus.getMileage() == null) {
                ecrire(bus, 0L, MileageOrigin.ATELIER, auteur(jwt), null);
            }
            return;
        }
        long demande = format(valeur, "le kilométrage du bus");
        // Le relevé n'est pas le plancher : il a déjà remonté à la fiche. Il sert à
        // nommer la sortie, l'homme et la date qui expliquent le chiffre d'en face.
        DernierReleve releve = dernierReleve(bus.getId());
        long plancher = plancher(bus);
        String raison = trimToNull(motif);
        if (demande < plancher) {
            if (raison == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        refus(bus, demande, plancher, releve));
            }
            ecrire(bus, demande, MileageOrigin.ATELIER, auteur(jwt), raison);
            audit.record("MILEAGE_BAISSE", "VEHICLE", bus.getId(),
                    "Compteur de " + FicheLabel.bus(bus) + " ramené de " + plancher + " km à " + demande
                            + " km — motif écrit par " + auteur(jwt) + " : " + raison, jwt);
            return;
        }
        ecrire(bus, demande, MileageOrigin.ATELIER, auteur(jwt), null);
    }

    /**
     * Le compteur relu par le technicien pendant une sortie. Ne demande rien à
     * l'atelier et ne fait jamais redescendre la fiche : si le bus est déjà connu
     * plus haut, le relevé reste sur la sortie et la fiche n'est pas touchée.
     *
     * @param par le nom de l'homme qui a relu le compteur, à défaut son adresse de compte
     */
    @Transactional
    public void releveTechnicien(String vehicleId, Long valeur, String par) {
        if (valeur == null) {
            return;
        }
        Vehicle bus = vehicles.findById(vehicleId).orElse(null);
        if (bus == null) {
            // Un bus peut quitter le portefeuille alors que ses sorties restent : le
            // relevé est déjà écrit sur la sortie, il n'y a plus de fiche à aligner.
            return;
        }
        long lu = format(valeur, "le compteur relu par le technicien");
        long connu = bus.getMileage() == null ? 0L : bus.getMileage();
        if (lu <= connu) {
            return;
        }
        ecrire(bus, lu, MileageOrigin.RELEVE_TECHNICIEN, par, null);
    }

    // ------------------------------------------------------------------ la lecture

    /**
     * Le plus haut des compteurs relus sur les sorties de ce bus, avec la sortie qui
     * l'a lu. Une saisie refusée se juge en nommant d'où vient le chiffre d'en face.
     */
    @Transactional(readOnly = true)
    public DernierReleve dernierReleve(String vehicleId) {
        Intervention meilleure = null;
        long km = -1;
        for (Intervention i : interventions.findRelevesBus(vehicleId)) {
            long valeur = Math.max(i.getBusMileage() == null ? 0L : i.getBusMileage(),
                    i.getBusMileageEnd() == null ? 0L : i.getBusMileageEnd());
            if (valeur > km) {
                km = valeur;
                meilleure = i;
            }
        }
        if (meilleure == null) {
            return null;
        }
        return new DernierReleve(km, meilleure.getNumber(), nomDuTechnicien(meilleure),
                meilleure.getReportedAt());
    }

    // ------------------------------------------------------------------ les règles

    /**
     * Ce que le portail sait du bus, et qui ne se laisse pas battre par une saisie :
     * la fiche. Les relevés des sorties y sont déjà remontés par cette même porte,
     * donc la comparer au dernier relevé ne ajouterait rien — sinon annuler une baisse
     * pourtant écrite et acceptée : l'atelier qui remet un compteur à 4 300 km avec
     * motif se verrait refuser 4 500 km le lendemain au nom d'une sortie ancienne.
     */
    private static long plancher(Vehicle bus) {
        return bus.getMileage() == null ? 0L : bus.getMileage();
    }

    /** Le refus lu à l'écran : la valeur d'en face, sa sortie, son auteur, sa date. */
    private String refus(Vehicle bus, long demande, long plancher, DernierReleve releve) {
        String dEnFace = releve != null && releve.km() >= plancher
                ? "le compteur relevé sur une sortie : " + releve.km() + " km, lu par "
                        + (releve.par() == null ? "le technicien" : releve.par()) + " à la sortie "
                        + releve.sortie() + " (" + date(releve.quand()) + ")"
                : "la valeur déjà sur la fiche du bus : " + plancher + " km";
        return "Le compteur saisi (" + demande + " km) est plus bas que " + dEnFace
                + ". Un compteur ne redescend pas : enregistre " + plancher + " km ou plus."
                + " Pour le remettre volontairement plus bas — compteur remplacé, relevé faux —"
                + " écris le motif dans la case « motif de la baisse » : ta raison restera sur la"
                + " fiche, comme pour la levée d'un rappel.";
    }

    /**
     * L'écriture, la seule du compteur. L'origine, l'heure et l'auteur partent avec
     * la valeur : un compteur sans provenance ne se défend pas devant un palier
     * contesté. Une valeur qui remonte n'a rien à justifier, donc son motif repart.
     */
    private void ecrire(Vehicle bus, long valeur, MileageOrigin origine, String par, String motif) {
        bus.setMileage(valeur);
        bus.setMileageOrigin(origine);
        bus.setMileageRecordedAt(LocalDateTime.now());
        bus.setMileageRecordedBy(truncate(par, 255));
        bus.setMileageCorrectionReason(motif == null ? null : truncate(motif, 500));
    }

    /** Le contrôle de forme, partagé avec la sortie de dépannage : un seul nombre crédible. */
    public static Long format(Long value, String label) {
        if (value == null) {
            return null;
        }
        if (value < 0 || value > MAX_COMPTEUR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Kilométrage invalide (" + label + ") : " + value
                            + ". Reprenez la valeur affichée au compteur.");
        }
        return value;
    }

    private String nomDuTechnicien(Intervention i) {
        if (i.getTechnicianId() == null) {
            return null;
        }
        Technician t = technicians.findById(i.getTechnicianId()).orElse(null);
        return t == null ? null : t.getName();
    }

    private static String auteur(Jwt jwt) {
        return jwt == null ? null : jwt.getClaimAsString("email");
    }

    private static String date(LocalDateTime value) {
        return value == null ? "—" : value.toLocalDate().toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
