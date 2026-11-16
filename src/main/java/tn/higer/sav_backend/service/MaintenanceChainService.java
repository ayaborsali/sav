package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.MissionRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * La chaîne des papiers de l'atelier : devis accepté → ordre de travail → bon de
 * livraison.
 *
 * Avant ce chantier, chaque papier était une île. {@code maintenance_items} est une
 * seule table coupée par {@code type} : rien ne disait de quoi un ordre était né,
 * et le numéro du devis ne vivait que dans une phrase de description, écrasable au
 * clavier. Trois règles tenaient à l'atelier, pas dans le portail :
 *
 * 1. <b>un ordre ne s'ouvre qu'après l'accord du client.</b> Tant que le devis est
 *    un brouillon, l'atelier n'a rien à exécuter : le client n'a pas dit oui.
 *    L'accord se lit sur l'état du devis (« validé » et après), jamais dans une
 *    case à cocher de plus.
 * 2. <b>un devis ne fait qu'un ordre.</b> Le second geste est refusé et nomme le
 *    papier déjà sorti : deux ordres pour un devis accepté, c'est un travail payé
 *    deux fois ou une facture en trop chez le client.
 * 3. <b>le lien se pose au clic, pas au clavier.</b> {@code source_item_id} et
 *    {@code mission_id} s'écrivent ici, au moment où le papier suivant naît. Le
 *    formulaire, lui, ne les touche pas : une origine tapée à la main n'est pas
 *    une preuve d'origine.
 *
 * Ce que le papier d'après reprend : le client, le bus, la demande, l'homme et les
 * lignes. Ce qu'il ne reprend pas : les montants — ils se recalculent sur les
 * lignes, comme partout — et, pour le bon de livraison, le palier du forfait. Un
 * palier se décompte une fois : c'est l'ordre qui l'a fait, le BL rend le bus.
 */
@Service
public class MaintenanceChainService {

    private final MaintenanceRepository documents;
    private final MissionRepository missions;
    private final SoldPlanService forfaits;
    private final AuditService audit;
    private final double tvaRate;

    public MaintenanceChainService(MaintenanceRepository documents, MissionRepository missions,
                                   SoldPlanService forfaits, AuditService audit,
                                   @Value("${app.tva.rate:19}") double tvaRate) {
        this.documents = documents;
        this.missions = missions;
        this.forfaits = forfaits;
        this.audit = audit;
        this.tvaRate = tvaRate;
    }

    /**
     * Le clic qui transforme un devis accepté en ordre de travail.
     *
     * L'ordre porte les lignes du devis sans ressaisie : l'atelier exécute ce qui a
     * été chiffré, pas ce qu'il en retient. Si le devis était le papier de la
     * mission, la case de la demande passe à l'ordre — la mission suit désormais le
     * travail en cours, et le devis reste visible à côté de lui dans « les papiers
     * de ce travail » parce qu'il porte le même {@code mission_id}.
     */
    @Transactional
    public MaintenanceItem ordreDepuisDevis(String devisId, Jwt jwt) {
        MaintenanceItem devis = documentDe(devisId);
        if (devis.getType() != MaintenanceType.DEVIS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + devis.getNumber() + " » n'est pas un devis : un ordre de travail ne se transforme "
                            + "pas depuis un ordre. Le bon de livraison se pose sur l'ordre qui a travaillé.");
        }
        // L'accord du client est le seul état d'un devis qui ouvre un travail. Depuis
        // V36 le devis a ses propres états (« envoyé », « accepté », « refusé ») et ils
        // s'écrivent par un clic sur le papier, plus par une case du formulaire : un
        // ordre né d'un devis que personne n'a signé est une facture chez un client
        // qui ne se souvient de rien.
        if (devis.getStatus() != MaintenanceStatus.VALIDATED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » n'a pas l'accord du client (état « "
                            + (devis.getStatus() == null ? "sans état" : devis.getStatus().label())
                            + " ») : " + pourquoiPasEncore(devis.getStatus()));
        }
        refuserSiDejaDonne(devis, MaintenanceType.OPERATION,
                "Le devis « " + devis.getNumber() + " » a déjà donné l'ordre");

        MaintenanceItem ordre = copier(devis, MaintenanceType.OPERATION, numeroDePapier("OR-"));
        ordre.setDescription("Ordre de travail du devis « " + devis.getNumber() + " » accepté (état « "
                + devis.getStatus().label() + " ») — " + origineDe(devis));
        forfaits.verifierRattachement(ordre);
        MaintenanceTotals.appliquer(ordre, tvaRate);
        documents.save(ordre);
        donnerLaDemandeA(ordre, devis.getId());
        audit.record("CREATE", "MAINTENANCE", ordre.getId(),
                "Ordre de travail « " + ordre.getNumber() + " » ouvert depuis le devis « "
                        + devis.getNumber() + " » accepté", jwt);
        return ordre;
    }

    /**
     * Le clic qui rend le bus et les pièces.
     *
     * Le bon de livraison reprend les lignes de l'ordre : ce sont elles que le
     * client rentre chez lui. Il ne reprend pas le palier du forfait — un palier ne
     * se décompte qu'une fois, et c'est l'ordre qui l'a acquitté (ou la sortie
     * d'entretien qui l'a fait). Le papier porte le bus restitué dans
     * {@code vehicle_id}, et sa description nomme le document dont il sort.
     */
    @Transactional
    public MaintenanceItem bonLivraisonDepuisOrdre(String ordreId, Jwt jwt) {
        MaintenanceItem ordre = documentDe(ordreId);
        if (ordre.getType() != MaintenanceType.OPERATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + ordre.getNumber() + " » n'est pas un ordre de travail : un bon de livraison rend "
                            + "le bus d'un travail fait à l'atelier, pas celui d'un devis.");
        }
        if (ordre.getStatus() == MaintenanceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'ordre « " + ordre.getNumber() + " » est annulé : il n'y a rien à restituer. Ouvrez "
                            + "l'ordre qui a réellement travaillé.");
        }
        // Le bus ne se rend que du travail terminé. Depuis V38, « Clôturer l'ordre » est
        // le geste qui l'écrit — opérations cochées, heures et preuve. Un bon de
        // livraison signé sur un ordre encore « À faire » rend un bus que l'atelier
        // n'a pas fini, et le client le découvre au démarrage.
        if (ordre.getStatus() != MaintenanceStatus.COMPLETED && ordre.getStatus() != MaintenanceStatus.DELIVERED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'ordre « " + ordre.getNumber() + " » n'est pas terminé (état « "
                            + (ordre.getStatus() == null ? "sans état"
                                    : ordre.getStatus().label(MaintenanceType.OPERATION))
                            + " ») : clôturez-le d'abord. Un bon de livraison rend le bus d'un travail fini, il"
                            + " ne prédit pas celui qui reste à faire.");
        }
        refuserSiDejaDonne(ordre, MaintenanceType.BL,
                "Le document « " + ordre.getNumber() + " » a déjà rendu son bon de livraison");

        MaintenanceItem bl = copier(ordre, MaintenanceType.BL, numeroDePapier("BL-"));
        // Le palier reste à l'ordre : le BL ne décompte pas une seconde fois la visite.
        bl.setSoldPlanId(null);
        bl.setPlanStepId(null);
        bl.setDescription("Restitution du bus et des pièces du document « " + ordre.getNumber() + " » — "
                + origineDe(ordre));
        // Aucun calcul de montant ici (V39) : le bon de livraison rend le bus et les
        // pièces, il ne les facture pas. Les montants posés sur un BL d'avant restent
        // lisibles tels qu'ils ont été écrits.
        documents.save(bl);
        audit.record("CREATE", "MAINTENANCE", bl.getId(),
                "Bon de livraison « " + bl.getNumber() + " » édité depuis le document « "
                        + ordre.getNumber() + " »", jwt);
        return bl;
    }

    /**
     * Ce qu'on efface ne laisse rien de cassé : un devis qui a déjà donné son ordre
     * ne se supprime pas, sinon l'ordre resterait sans la facture qu'il a suivie. Le
     * refus nomme l'enfant — c'est lui qu'on annule ou qu'on supprime d'abord.
     */
    @Transactional(readOnly = true)
    public void refuserSiEnfantDejaNe(MaintenanceItem papier) {
        List<MaintenanceItem> enfants = documents.findBySourceItemId(papier.getId());
        if (enfants.isEmpty()) {
            return;
        }
        String noms = enfants.stream().map(MaintenanceItem::getNumber).collect(Collectors.joining(" » « "));
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Le document « " + papier.getNumber() + " » a déjà fait naître « " + noms
                        + " ». Un papier d'origine ne s'efface pas derrière celui qu'il a produit : supprimez "
                        + "d'abord le papier de la chaîne.");
    }

    // ------------------------------------------------------------------ les pièces

    /** Le papier d'après, avec les mêmes lignes et le même bus, sans rien deviner de plus. */
    private MaintenanceItem copier(MaintenanceItem source, MaintenanceType type, String numero) {
        MaintenanceItem copie = new MaintenanceItem();
        copie.setId("maint-" + UUID.randomUUID());
        copie.setType(type);
        copie.setNumber(numero);
        copie.setVehicleId(source.getVehicleId());
        copie.setClientId(source.getClientId());
        copie.setDate(LocalDate.now());
        copie.setStatus(MaintenanceStatus.DRAFT);
        copie.setTechnician(source.getTechnician());
        copie.setSoldPlanId(source.getSoldPlanId());
        copie.setPlanStepId(source.getPlanStepId());
        copie.setMissionId(source.getMissionId());
        copie.setSourceItemId(source.getId());
        for (MaintenanceDetail ligne : source.getDetails()) {
            MaintenanceDetail d = new MaintenanceDetail();
            d.setReference(ligne.getReference());
            d.setLabel(ligne.getLabel());
            d.setQuantity(ligne.getQuantity());
            d.setUnitPrice(ligne.getUnitPrice());
            d.setQuantityText(ligne.getQuantityText());
            // La nature et le fournisseur suivent le travail : l'ordre exécute ce qui a
            // été chiffré, pièce par pièce, et un bon de livraison qui ne dit pas qui a
            // fourni la pièce ne la rend pas.
            d.setNature(ligne.getNature());
            d.setSuppliedBy(ligne.getSuppliedBy());
            // Le coche « fait » et les heures suivent aussi (V39) : le bon de livraison
            // rend le travail TEL QU'IL A ÉTÉ FAIT. Une table « Travaux réalisés » vide
            // enverrait l'atelier recompter les heures sur l'ordre, et un papier signé par
            // le client ne se re-saisit pas.
            d.setDone(ligne.getDone());
            d.setRealHours(ligne.getRealHours());
            // La trace de la pièce du catalogue descend avec la copie (V46) : le BL
            // peut dire quelle pièce est partie avec le bus, sans relire le devis.
            d.setPieceId(ligne.getPieceId());
            copie.getDetails().add(d);
        }
        return copie;
    }

    /** Le devis ou l'ordre était peut-être le papier de la demande : le travail en cours change de case. */
    private void donnerLaDemandeA(MaintenanceItem nouveau, String sourceId) {
        if (nouveau.getMissionId() == null) {
            return;
        }
        Mission m = missions.findById(nouveau.getMissionId()).orElse(null);
        if (m == null || !sourceId.equals(m.getMaintenanceItemId())) {
            return;
        }
        m.setMaintenanceItemId(nouveau.getId());
        missions.save(m);
    }

    private void refuserSiDejaDonne(MaintenanceItem papier, MaintenanceType dejaFait, String debutDuMessage) {
        MaintenanceItem enfant = documents.findBySourceItemId(papier.getId()).stream()
                .filter(e -> e.getType() == dejaFait)
                .findFirst().orElse(null);
        if (enfant == null) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                debutDuMessage + " « " + enfant.getNumber() + " » : un même travail ne se pose pas deux fois. "
                        + (dejaFait == MaintenanceType.OPERATION
                                ? "Relisez l'ordre qui court, ou annulez-le avant d'en ouvrir un autre."
                                : "Un bus ne se restitue pas deux fois : relisez le bon de livraison existant."));
    }

    /** Ce qui dit d'où vient le papier d'origine : la demande, sinon le dossier ou le rappel écrits dessus. */
    private static String origineDe(MaintenanceItem papier) {
        String description = papier.getDescription();
        return description == null || description.isBlank() ? "demande du client" : description;
    }

    private MaintenanceItem documentDe(String id) {
        return documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document de maintenance introuvable."));
    }

    /**
     * Le numéro que le serveur pose quand le poste n'en tape pas : DV- pour un devis,
     * OR- pour un ordre, BL- pour un bon de livraison, sur la suite de l'année. Le
     * même compteur que celui des clics de la chaîne et de la mission — deux
     * numérotations dans la même table donnent tôt ou tard deux papiers du même
     * numéro, et un numéro posé deux fois est une facture refusée à l'impression.
     */
    public String prochainNumero(MaintenanceType type) {
        return numeroDePapier(switch (type) {
            case DEVIS -> "DV-";
            case OPERATION -> "OR-";
            case BL -> "BL-";
        });
    }

    /** Le remède que l'atelier doit connaître quand le bouton est refusé : nommer l'état qui manque. */
    private static String pourquoiPasEncore(MaintenanceStatus etat) {
        return switch (etat == null ? MaintenanceStatus.DRAFT : etat) {
            case DRAFT -> "il est encore brouillon. Envoyez-le au client, puis enregistrez son accord.";
            case SENT -> "il est parti chez le client sans réponse. Enregistrez l'accord — le devis signé doit"
                    + " être joint — puis refaites ce geste.";
            case REFUSED -> "le client l'a refusé. Un travail refusé ne s'ouvre pas : établissez un nouveau"
                    + " devis s'il change d'avis.";
            case CANCELLED -> "il est annulé : personne ne l'a accepté. Ouvrez un nouveau devis si le client"
                    + " change d'avis.";
            default -> "seul un devis accepté par le client ouvre un ordre de travail.";
        };
    }

    /**
     * OR- et BL- prennent la même suite que l'écran Maintenance et la mission : une
     * seconde numérotation donnerait deux papiers du même numéro, et un numéro posé
     * deux fois est une facture refusée à l'impression.
     */
    private String numeroDePapier(String prefix) {
        String annee = prefix + LocalDate.now().getYear() + "-";
        return NumberSequence.suivant(annee, documents.findNumbersByPrefix(annee), 3,
                documents::existsByNumberIgnoreCase);
    }
}
