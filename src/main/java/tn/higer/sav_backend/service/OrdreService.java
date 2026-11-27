package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenanceDtos.ClotureRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.dto.OrdreDtos.EtatClotureDto;
import tn.higer.sav_backend.dto.OrdreDtos.FicheResponse;
import tn.higer.sav_backend.dto.OrdreDtos.ForfaitDto;
import tn.higer.sav_backend.dto.OrdreDtos.JustificatifDto;
import tn.higer.sav_backend.dto.OrdreDtos.OrigineDto;
import tn.higer.sav_backend.dto.PhotoDtos.PhotoResponse;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * L'ordre de travail : le papier qui dit que l'atelier a touché au bus.
 *
 * Trois gestes, et chacun ferme une porte qui était restée grande ouverte :
 *
 * 1. <b>il ne chiffre pas.</b> Le prix est sur le devis que le client a signé ; le
 *    remettre sur l'ordre, c'est avoir deux montants à défendre pour un même
 *    travail. Les ordres déjà enregistrés gardent les leurs — le portail n'efface
 *    pas ce qu'il a écrit, il cesse d'en écrire.
 * 2. <b>il se clôt, il ne se tape pas.</b> « Terminé » s'obtient par
 *    {@link #cloturer} : toutes les opérations cochées, les heures réelles quand le
 *    papier acquitte un palier du forfait, et une preuve. La date s'écrit ici, pas
 *    dans la case état du formulaire.
 * 3. <b>la preuve se relit, elle ne se redemande pas.</b> L'ordre né d'une sortie
 *    reprend les clichés de cette sortie — le technicien les a versés dehors, les
 *    redemander à l'atelier voudrait dire photographier un bus qui est déjà rentré.
 *    Trois portes, une seule suffit : la photo « après » de la sortie, la fiche
 *    d'atelier signée jointe au papier, ou une raison écrite. La troisième est la
 *    même que pour une sortie sans photo : on peut clôturer sans cliché, jamais sans
 *    l'avoir écrit — et ce qui est écrit part au journal.
 *
 * Ce que la clôture change ailleurs : le palier du forfait passe à « fait » (par
 * {@link SoldPlanService}, seule porte d'écriture de cet avancement), et le bon de
 * livraison devient possible — un bus ne se rend que du travail terminé.
 */
@Service
public class OrdreService {

    private final MaintenanceRepository documents;
    private final MissionRepository missions;
    private final InterventionRepository sorties;
    private final InterventionPhotoRepository photos;
    private final MaintenanceAttachmentRepository piecesJointes;
    private final SoldPlanRepository forfaitsVendus;
    private final ItemPartService piecesPosees;
    private final SoldPlanService forfaits;
    private final BonLivraisonService bons;
    private final AuditService audit;

    public OrdreService(MaintenanceRepository documents, MissionRepository missions,
                        InterventionRepository sorties, InterventionPhotoRepository photos,
                        MaintenanceAttachmentRepository piecesJointes, SoldPlanRepository forfaitsVendus,
                        ItemPartService piecesPosees, SoldPlanService forfaits, BonLivraisonService bons,
                        AuditService audit) {
        this.documents = documents;
        this.missions = missions;
        this.sorties = sorties;
        this.photos = photos;
        this.piecesJointes = piecesJointes;
        this.forfaitsVendus = forfaitsVendus;
        this.piecesPosees = piecesPosees;
        this.forfaits = forfaits;
        this.bons = bons;
        this.audit = audit;
    }

    // ---------------------------------------------------------------------- la clôture

    /**
     * Le clic qui termine l'ordre.
     *
     * Le refus se fait avec le motif exact, dans l'ordre où l'atelier le corrige :
     * d'abord les opérations pas faites, ensuite les heures, ensuite la preuve. Un
     * « clôture refusée » sans nom de ligne laisse l'homme relire ses dix lignes une
     * par une — et le dixième papier qu'il annule par erreur est un palier perdu.
     */
    @Transactional
    public MaintenanceItem cloturer(String ordreId, ClotureRequest request, Jwt jwt) {
        MaintenanceItem ordre = ordreDe(ordreId);
        String motif = propre(request == null ? null : request.motif());

        Besoin besoin = besoinDe(ordre, motif);
        if (!besoin.pret()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Clôture de l'ordre « " + ordre.getNumber() + " » refusée : " + besoin.manquants());
        }
        // Le palier se vérifie AVANT l'écriture : un 409 ne doit pas laisser un ordre
        // terminé avec une date de clôture et un palier qui n'est pas le sien.
        forfaits.verifierRattachement(ordre);

        ordre.setStatus(MaintenanceStatus.COMPLETED);
        ordre.setClosedOn(LocalDate.now());
        if (besoin.parLaRaison()) {
            ordre.setClosureReason(motif);
        }
        documents.save(ordre);
        forfaits.appliquerSuivi(ordre, ordre.getSoldPlanId(), ordre.getPlanStepId(), jwt);
        audit.record("UPDATE", "MAINTENANCE", ordre.getId(),
                "Ordre de travail « " + ordre.getNumber() + " » clôturé le " + ordre.getClosedOn()
                        + " — preuve : " + besoin.preuve()
                        + (besoin.auForfait() ? ", " + motDuPalier(ordre) + " acquitté"
                                : ", hors forfait"), jwt);
        return ordre;
    }

    /**
     * Ce qui manque pour clôturer, lu par le serveur avant le clic : l'écran colore, il
     * ne décide pas. {@code pret} veut dire « le clic passera sans rien taper de plus »
     * — le motif écrit à la main peut suffire à la preuve, et le bouton ne se verrouille
     * pas pour autant : la raison écrite EST une porte licite.
     */
    @Transactional(readOnly = true)
    public EtatClotureDto etatDeCloture(String ordreId) {
        MaintenanceItem ordre = ordreDe(ordreId);
        Besoin besoin = besoinDe(ordre, ordre.getClosureReason());
        return besoin.etat(ordre);
    }

    // ------------------------------------------------------------------ les gardes du formulaire

    /**
     * Un ordre ne naît pas terminé. Le formulaire pouvait écrire « livré » sur une
     * ligne vide : le palier du forfait se cochaient sans qu'aucune opération soit
     * cochée, et la prime se payait sur une déclaration.
     */
    public void verifierNaissance(MaintenanceType type, MaintenanceStatus demandee) {
        if (type != MaintenanceType.OPERATION || demandee == null
                || demandee == MaintenanceStatus.DRAFT || demandee == MaintenanceStatus.IN_PROGRESS) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Un ordre de travail ne naît pas « " + demandee.label(MaintenanceType.OPERATION).toLowerCase()
                        + " » : il commence « À faire » et il se termine par le clic sur « Clôturer l'ordre »."
                        + " La clôture écrit la date, réclame les opérations cochées, les heures réelles s'il"
                        + " s'agit d'un palier du forfait, et une preuve — un état tapé au clavier n'est aucune"
                        + " de ces quatre choses.");
    }

    /**
     * La case « Anomalies constatées » n'existe que sur l'ordre de travail. Un devis ou
     * un bon de livraison qui en reçoit une ne se laisse pas écrire en silence : la
     * colonne serait là sans que personne ne la relit à l'écran, et une anomalie notée
     * sur un papier qui ne travaille pas ne sera jamais vue sur le bus.
     */
    public void verifierAnomalies(MaintenanceType type, String valeur) {
        if (type == MaintenanceType.OPERATION || propre(valeur) == null) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Les anomalies se notent sur un ordre de travail, pas sur un « "
                        + (type == null ? "papier sans nature" : type.label()) + " » : c'est l'atelier qui"
                        + " voit ce qui cloche sur le bus pendant qu'il y travaille, et le papier qui porte ce"
                        + " travail est l'ordre. Reprenez l'ordre né de ce document pour l'écrire.");
    }

    /**
     * Ce que le formulaire peut changer sur un ordre, et ce qu'il ne peut pas.
     *
     * Trois états se touchent à l'écran parce qu'ils se constatent sur le bus : « À
     * faire », « En cours » (l'équipe s'y est mise) et « Annulé » — ce dernier est la
     * porte qui libère le palier quand le papier s'est trompé de visite. « Terminé »
     * ne se tape pas : il s'obtient par {@link #cloturer}, qui réclame les opérations
     * cochées, les heures réelles s'il acquitte un palier, et une preuve — un état
     * « terminé » écrit au clavier serait une visite sans preuve.
     *
     * Un ordre déjà clos ne se réécrit plus, à part ce même « Annulé » : c'est lui qui
     * remet le palier à faire. Les états du devis (« envoyé », « accepté », « refusé »)
     * ne se lisent pas sur un ordre de travail.
     *
     * <b>SAUF si son bon de livraison est signé</b> (V39) : là, même l'annulation se
     * refuse et le refus nomme le papier signé. Un bus que le client a repris avec deux
     * papiers en main ne se remet pas en travail à faire.
     */
    public void verifierModification(MaintenanceItem avant, MaintenanceStatus demandee) {
        if (avant.getType() != MaintenanceType.OPERATION) {
            return;
        }
        MaintenanceStatus etat = avant.getStatus() == null ? MaintenanceStatus.DRAFT : avant.getStatus();
        boolean clos = etat == MaintenanceStatus.COMPLETED || etat == MaintenanceStatus.DELIVERED;
        if (clos) {
            // « null » aussi est refusé : le formulaire qui envoie un état vide réécrit
            // quand même les lignes du papier, et ses lignes à lui sont la preuve.
            if (demandee == MaintenanceStatus.CANCELLED) {
                MaintenanceItem signe = bonSigneDe(avant);
                if (signe != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "L'ordre « " + avant.getNumber() + " » ne s'annule pas : son bon de livraison « "
                                    + signe.getNumber() + " » est signé" + dateDe(signe) + " par "
                                    + signe.getSignataireNom() + ". Le client a tenu les deux papiers en main et"
                                    + " a repris le bus. Ce qui s'est passé après se note sur un dossier, pas sur"
                                    + " un papier signé qu'on efface.");
                }
                return;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'ordre « " + avant.getNumber() + " » est « " + etat.label(MaintenanceType.OPERATION) + " »"
                            + (avant.getClosedOn() == null ? "" : " depuis le " + avant.getClosedOn())
                            + " : ses lignes ne se réécrivent plus. C'est lui qui a coché le palier du forfait, et"
                            + " une opération changée après la clôture est une preuve fausse. Si ce papier s'est"
                            + " trompé de travail, passez-le à « Annulé » — le palier se remet à faire dans le"
                            + " même geste — puis reprenez le bon papier.");
        }
        if (demandee == null || demandee == etat) {
            return;
        }
        if (demandee == MaintenanceStatus.CANCELLED) {
            return;
        }
        if (etat == MaintenanceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'ordre « " + avant.getNumber() + " » est annulé : il ne se reprend pas en « "
                            + demandee.label(MaintenanceType.OPERATION) + " ». Le palier qu'il devait est remis à"
                            + " faire, et un papier annulé qui repart travaillerait sous deux numéros.");
        }
        boolean coursDuTravail = (etat == MaintenanceStatus.DRAFT || etat == MaintenanceStatus.IN_PROGRESS)
                && (demandee == MaintenanceStatus.DRAFT || demandee == MaintenanceStatus.IN_PROGRESS);
        if (coursDuTravail) {
            return;
        }
        if (demandee == MaintenanceStatus.COMPLETED || demandee == MaintenanceStatus.DELIVERED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'état de l'ordre « " + avant.getNumber() + " » ne se tape pas (« "
                            + demandee.label(MaintenanceType.OPERATION) + " » demandé) : il s'obtient par"
                            + " « Clôturer l'ordre », qui réclame les opérations cochées, les heures réelles si ce"
                            + " papier acquitte un palier du forfait, et une preuve — photo « après » de la sortie,"
                            + " fiche d'atelier signée, ou raison écrite.");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "« " + demandee.label() + " » n'est pas un état d'un ordre de travail ("
                        + avant.getNumber() + " est en base « " + etat.label(MaintenanceType.OPERATION)
                        + " »). Un ordre lit « À faire », « En cours », « Terminé » ou « Annulé » ; « envoyé »,"
                        + " « accepté » et « refusé » sont les états du devis, et se règlent sur le devis.");
    }

    // ------------------------------------------------------------------------- la fiche

    /**
     * La fiche d'un papier de l'atelier : le papier, ses origines nommées, le forfait
     * qu'il touche, les photos de sa sortie d'origine, les pièces posées dessus, et
     * l'état de sa clôture. Une seule requête pour que l'écran n'additionne pas six
     * appels afin d'afficher une ligne — et pour que les chiffres viennent tous du
     * serveur.
     */
    @Transactional(readOnly = true)
    public FicheResponse fiche(String id) {
        MaintenanceItem papier = papierDe(id);
        MaintenanceItem source = papier.getSourceItemId() == null ? null
                : documents.findById(papier.getSourceItemId()).orElse(null);
        MaintenanceItem devis = devisAuDessus(source);
        Mission mission = missionDe(papier);
        Intervention sortie = sortieDe(papier, mission);

        List<InterventionPhoto> cliches = sortie == null ? List.of()
                : photos.findByInterventionIdOrderByUploadedAtAsc(sortie.getId());
        return new FicheResponse(
                MaintenanceResponse.from(papier),
                new OrigineDto(source == null ? null : source.getNumber(),
                        source == null || source.getType() == null ? null : source.getType().label(),
                        devis == null ? null : devis.getNumber(),
                        mission == null ? null : mission.getId(),
                        mission == null ? null : mission.getNumber(),
                        sortie == null ? null : sortie.getId(),
                        sortie == null ? null : sortie.getNumber()),
                forfaitDe(papier),
                cliches.stream().map(PhotoResponse::from).toList(),
                piecesPosees.pourDocument(papier.getId()),
                papier.getType() == MaintenanceType.OPERATION
                        ? etatDeCloture(papier.getId())
                        : null,
                papier.getType() == MaintenanceType.BL ? bons.blocDeBon(papier, sortie) : null);
    }

    /** Les origines, en lecture seule. */
    private Mission missionDe(MaintenanceItem papier) {
        return papier.getMissionId() == null ? null
                : missions.findById(papier.getMissionId()).orElse(null);
    }

    /**
     * La sortie dont ce papier vient : la mission le dit si elle existe, sinon la
     * sortie garde elle-même la référence du papier écrit à sa clôture (H1) — les deux
     * chemins mènent aux mêmes clichés, et aucun des deux ne se devine.
     *
     * Un bon de livraison, lui, n'est jamais le papier que la sortie nomme : il rend le
     * bus d'un travail déjà sorti. Sa sortie — et donc ses clichés et son intervenant —
     * se relit sur le papier dont il est né, sinon la maquette serait vide pile là où le
     * client veut voir l'homme et ses heures.
     */
    private Intervention sortieDe(MaintenanceItem papier, Mission mission) {
        if (mission != null && mission.getInterventionId() != null) {
            Intervention parMission = sorties.findById(mission.getInterventionId()).orElse(null);
            if (parMission != null) {
                return parMission;
            }
        }
        Intervention parPapier = sortieDePapier(papier.getId());
        if (parPapier != null) {
            return parPapier;
        }
        return papier.getSourceItemId() == null ? null : sortieDePapier(papier.getSourceItemId());
    }

    private Intervention sortieDePapier(String papierId) {
        return sorties.findByMaintenanceItemIdOrderByReportedAtDesc(papierId).stream()
                .findFirst().orElse(null);
    }

    /** Le forfait engagé, le palier nommé et son état — ce que la maquette affiche en bas de fiche. */
    private ForfaitDto forfaitDe(MaintenanceItem papier) {
        if (papier.getSoldPlanId() == null) {
            return null;
        }
        SoldPlan vendu = forfaitsVendus.findById(papier.getSoldPlanId()).orElse(null);
        if (vendu == null) {
            return null;
        }
        int faits = (int) vendu.getSteps().stream().filter(s -> s.getStatus() == SoldPlanStepStatus.DONE).count();
        SoldPlanStep suivi = papier.getPlanStepId() == null ? null : vendu.getSteps().stream()
                .filter(s -> s.getStep().getId().equals(papier.getPlanStepId())).findFirst().orElse(null);
        String tenuPar = null;
        if (suivi != null && suivi.getMaintenanceItemId() != null) {
            tenuPar = documents.findById(suivi.getMaintenanceItemId())
                    .map(MaintenanceItem::getNumber).orElse(null);
        }
        return new ForfaitDto(vendu.getPlan().getName(),
                suivi == null ? null : suivi.getStep().getLabel(),
                suivi == null ? null : (suivi.getStatus() == SoldPlanStepStatus.DONE ? "Fait" : "À faire"),
                faits, vendu.getSteps().size(), tenuPar);
    }

    /** Le palier que ce papier doit acquitter, nommé dans la phrase de clôture. */
    private String motDuPalier(MaintenanceItem papier) {
        ForfaitDto forfait = forfaitDe(papier);
        return forfait == null || forfait.labelPalier() == null
                ? "le palier du forfait" : "le palier « " + forfait.labelPalier() + " »";
    }

    // ---------------------------------------------------------------------- ce qui manque

    /**
     * Le besoin de la clôture, calculé une seule fois pour le GET (l'écran colore) et
     * pour le POST (le refus). Deux copies de cette règle donneraient deux réponses à
     * la même question — et « l'écran dit prêt, le serveur refuse » est un piège déjà
     * payé sur ce portail.
     */
    private Besoin besoinDe(MaintenanceItem ordre, String motif) {
        boolean auForfait = ordre.getSoldPlanId() != null && ordre.getPlanStepId() != null;
        List<String> nonCochees = new ArrayList<>();
        List<String> heures = new ArrayList<>();
        List<String> raisons = new ArrayList<>();
        if (ordre.getDetails().isEmpty()) {
            raisons.add("ce papier n'a aucune opération : rien à cocher, donc rien à clôturer — reprenez"
                    + " les lignes du devis dont il est né, ou annulez-le");
        }
        int numeroDeLigne = 0;
        int taches = 0;
        for (MaintenanceDetail ligne : ordre.getDetails()) {
            numeroDeLigne++;
            // Ce qui se coche, c'est une TÂCHE de l'atelier : la main-d'œuvre. Une pièce se
            // pose — sa quantité consommée s'écrit aux pièces posées — et un déplacement se
            // paie au forfait de sortie : les cocher n'avait aucun sens et obligeait l'atelier
            // à cocher des lignes artificielles pour clôturer. Une ligne sans nature — les
            // papiers nés avant V36 — reste une tâche, comme avant.
            if (ligne.getNature() == LineNature.PART || ligne.getNature() == LineNature.TRAVEL) {
                continue;
            }
            taches++;
            String nom = nomDe(ligne, numeroDeLigne);
            if (!Boolean.TRUE.equals(ligne.getDone())) {
                nonCochees.add(nom);
                continue;
            }
            // Les heures se demandent sur la main-d'œuvre, nulle part ailleurs : une pièce
            // posée a une quantité, pas un temps, et un déplacement se paie au forfait de
            // sortie. Une ligne sans nature — les papiers nés avant V36 — ne bloque pas la
            // clôture : réclamer là un temps que personne n'a saisi ferait attendre une
            // visite déjà finie.
            if (auForfait && ligne.getNature() == LineNature.LABOR
                    && (ligne.getRealHours() == null || ligne.getRealHours() <= 0)) {
                heures.add(nom);
            }
        }

        if (!ordre.getDetails().isEmpty() && taches == 0) {
            raisons.add("ce papier n'a aucune tâche de main-d'œuvre : il ne porte que des pièces ou un"
                    + " déplacement, donc rien à cocher — ajoutez la tâche qui a été faite, ou annulez-le");
        }

        Mission mission = missionDe(ordre);
        Intervention sortie = sortieDe(ordre, mission);
        boolean photoApres = sortie != null
                && photos.existsByInterventionIdAndAngle(sortie.getId(), PhotoAngle.AFTER);
        boolean ficheSignee = piecesJointes.countByMaintenanceItemId(ordre.getId()) > 0;
        boolean parLaRaison = !photoApres && !ficheSignee && motif != null && !motif.isBlank();

        if (!nonCochees.isEmpty()) {
            raisons.add("il reste " + nonCochees.size() + " opération(s) non cochée(s) : « "
                    + String.join(" », « ", borne(nonCochees)) + " »");
        }
        if (!heures.isEmpty()) {
            raisons.add("les heures réelles manquent sur « " + String.join(" », « ", borne(heures))
                    + " » — ce papier doit acquitter " + motDuPalier(ordre)
                    + ", et le temps passé est la seule chose qui prouve le travail de l'homme");
        }
        if (!(photoApres || ficheSignee || parLaRaison)) {
            raisons.add("aucune preuve : " + (sortie == null
                    ? "ce papier n'est rattaché à aucune sortie et ne porte pas de fiche d'atelier"
                        + " signée — joignez-la, ou écrivez pourquoi il n'y a ni photo ni fiche"
                    : "la sortie " + sortie.getNumber() + " n'a pas de photo « "
                        + PhotoAngle.AFTER.label() + " » et ce papier n'a pas de fiche d'atelier"
                        + " signée en pièce jointe — joignez la fiche, ou écrivez pourquoi la photo"
                        + " n'est pas là"));
        }

        String preuve = photoApres ? "la photo « " + PhotoAngle.AFTER.label() + " » de la sortie "
                        + (sortie == null ? "?" : sortie.getNumber())
                : ficheSignee ? "la fiche d'atelier signée jointe au papier"
                : parLaRaison ? "raison écrite : « " + motif.trim() + " »"
                : "aucune preuve";

        return new Besoin(nonCochees, heures, photoApres, ficheSignee, parLaRaison, preuve, auForfait,
                sortie == null ? null : sortie.getId(), sortie == null ? null : sortie.getNumber(), raisons);
    }

    /** Au-delà de trois noms, la liste abrège : un refus qui occupe l'écran n'est plus lu. */
    private static List<String> borne(List<String> noms) {
        return noms.size() <= 3 ? noms : new ArrayList<>(noms.subList(0, 3));
    }

    private static String nomDe(MaintenanceDetail ligne, int numero) {
        String reference = propre(ligne.getReference());
        String label = propre(ligne.getLabel());
        if (reference != null && label != null) {
            return reference + " " + label;
        }
        if (label != null) {
            return label;
        }
        if (reference != null) {
            return reference;
        }
        return "ligne " + numero;
    }

    /**
     * Le besoin de la clôture, sous sa forme interne : la même matière sert au refus
     * écrit (POST) et à l'affichage (GET).
     */
    private record Besoin(List<String> nonCochees, List<String> heures, boolean photoApres,
                          boolean ficheSignee, boolean parLaRaison, String preuve, boolean auForfait,
                          String sortieId, String sortieNumber, List<String> motifs) {

        boolean pret() {
            return motifs.isEmpty();
        }

        String manquants() {
            return motifs.size() == 1 ? motifs.get(0) + "." : String.join(" ; ", motifs) + ".";
        }

        EtatClotureDto etat(MaintenanceItem ordre) {
            MaintenanceStatus situation = ordre.getStatus() == null
                    ? MaintenanceStatus.DRAFT : ordre.getStatus();
            boolean deja = situation == MaintenanceStatus.COMPLETED || situation == MaintenanceStatus.DELIVERED;
            boolean annule = situation == MaintenanceStatus.CANCELLED;
            List<String> ceQuiManque = new ArrayList<>(motifs);
            if (deja) {
                ceQuiManque.add(0, "cet ordre est déjà « " + situation.label(MaintenanceType.OPERATION) + " »"
                        + (ordre.getClosedOn() == null ? "" : " depuis le " + ordre.getClosedOn()));
            }
            if (annule) {
                ceQuiManque.add(0, "cet ordre est annulé : il ne se clôt pas, on en ouvre un autre");
            }
            String justificatifMessage = photoApres
                    ? "La photo « " + PhotoAngle.AFTER.label() + " » de la sortie " + sortieNumber + " suffit."
                    : ficheSignee ? "La fiche d'atelier signée est jointe à ce papier."
                    : parLaRaison ? "La raison écrite tient lieu de preuve ; elle part au journal."
                    : "Il manque une preuve : la photo « " + PhotoAngle.AFTER.label() + " » de la sortie,"
                            + " la fiche d'atelier signée en pièce jointe, ou la raison écrite.";
            JustificatifDto justificatif = new JustificatifDto(sortieId, sortieNumber, photoApres, ficheSignee,
                    parLaRaison, justificatifMessage);
            String message = deja ? "Ordre déjà clos" : pret() ? "Prêt à clôturer" : "Clôture refusée : "
                    + manquants();
            return new EtatClotureDto(pret() && !deja && !annule, nonCochees, heures, justificatif,
                    ceQuiManque, message);
        }
    }

    // ------------------------------------------------------------------------- petits lecteurs

    private MaintenanceItem papierDe(String id) {
        return documents.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Document de maintenance introuvable."));
    }

    /**
     * Le devis en tête de chaîne : la source elle-même quand le papier naît d'un devis,
     * ou la source de la source quand il naît d'un ordre né d'un devis. Un lien de plus,
     * et la chaîne n'est plus une chaîne — elle ne se remonte pas à l'infini.
     */
    private MaintenanceItem devisAuDessus(MaintenanceItem source) {
        if (source == null) {
            return null;
        }
        if (source.getType() == MaintenanceType.DEVIS) {
            return source;
        }
        return source.getSourceItemId() == null ? null
                : documents.findById(source.getSourceItemId())
                        .filter(devant -> devant.getType() == MaintenanceType.DEVIS)
                        .orElse(null);
    }

    /**
     * Le bon de livraison SIGNÉ né de cet ordre, s'il y en a un. Tant qu'il existe,
     * l'ordre est verrouillé avec son papier : le client a repris le bus en tenant les
     * deux en main, et une annulation tardive effacerait la seule trace du travail fait.
     */
    private MaintenanceItem bonSigneDe(MaintenanceItem ordre) {
        return documents.findBySourceItemId(ordre.getId()).stream()
                .filter(enfant -> enfant.getType() == MaintenanceType.BL)
                .filter(enfant -> enfant.getStatus() == MaintenanceStatus.SIGNED)
                .findFirst().orElse(null);
    }

    private static String dateDe(MaintenanceItem bl) {
        return bl.getSignedOn() == null ? "" : " le " + bl.getSignedOn();
    }

    private MaintenanceItem ordreDe(String id) {
        MaintenanceItem papier = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document de maintenance introuvable."));
        if (papier.getType() != MaintenanceType.OPERATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + papier.getNumber() + " » n'est pas un ordre de travail ("
                            + (papier.getType() == null ? "type inconnu" : papier.getType().label()) + ") : on"
                            + " ne clôture que le papier qui dit que l'atelier a travaillé.");
        }
        return papier;
    }

    private static String propre(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
