package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.PlanAlertDto;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.PlanAlertListResponse;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.ScanResponse;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.WaiveRequest;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanAlert;
import tn.higer.sav_backend.model.SoldPlanAlertKind;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.SoldPlanAlertRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.security.Access;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Le rappel des paliers : ce que LE COMPTEUR du bus réclame.
 *
 * Un forfait vendu ne se suit pas à la mémoire. Le catalogue pose une borne par
 * palier — un kilométrage, des jours, souvent les deux — et tant que personne ne
 * comparait cette borne à l'odomètre du bus, « visite des 40 000 km » restait une
 * ligne écrite dans un fichier. Ici, le serveur fait la comparaison et pose la
 * question.
 *
 * Trois règles, prises dans le même esprit que les alertes de dépannage :
 *  1. <b>km OU jours, au premier atteint.</b> Le kilométrage décide pour un bus qui
 *     roule, les jours pour un bus qui dort à l'atelier ; une visite des 30 jours
 *     due ne peut pas être repoussée par un compteur bas. La borne qui a décidé est
 *     nommée, l'autre reste lisible dans le message ;
 *  2. <b>le compteur est celui de la fiche du bus</b>, et rien d'autre. Une seule
 *     écriture le tient ({@link VehicleMileageService}) : le relevé du technicien
 *     sur une sortie le fait monter, une baisse volontaire s'écrit avec un motif.
 *     Le rappel ne recompte pas les sorties à son compte — deux calculs du même
 *     chiffre finissent par se contredire dès qu'une baisse est décidée ;
 *  3. <b>une alerte n'est pas une facture.</b> Elle ne bloque ni ne compte rien :
 *     un humain répond, par le document qui fait le palier, ou par une raison écrite
 *     qui lève le rappel. Ce qui se règle tout seul — palier fait, forfait fermé,
 *     compteur corrigé à la baisse — ferme sa ligne sans décision.
 *
 * Un palier déjà fait (DONE) n'est jamais rappelé, et un forfait qui n'est plus
 * ACTIF ne produit plus rien : ses paliers sont clos ou annulés, les rappeler
 * remettrait en jeu une engagement terminé.
 *
 * La levée est réservée aux comptes qui écrivent dans les forfaits. Le technicien
 * et le rôle consultation lisent, ils ne se donnent pas raison à eux-mêmes.
 */
@Service
public class SoldPlanAlertService {

    private final SoldPlanRepository soldPlans;
    private final SoldPlanAlertRepository alerts;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final AuditService audit;

    /** Une ligne de rappel dit quelle sortie l'a prise en charge : lue, jamais écrite ici. */
    private final InterventionRepository sorties;

    /**
     * Et quel dossier de visite a déjà été ouvert sur ce palier : la même question,
     * lue de l'autre côté. Un rappel et un dossier « visites » parlent de la même
     * visite ; ils doivent se voir.
     */
    private final SavCaseRepository dossiers;

    /**
     * Le tour automatique. Coupable par une propriété, comme celui du dépannage :
     * deux instances sur la même base ne doivent pas parler toutes les deux, et un
     * poste de démonstration n'a pas à consommer un écran que l'atelier ne lit pas.
     */
    private final boolean autoScan;

    public SoldPlanAlertService(SoldPlanRepository soldPlans, SoldPlanAlertRepository alerts,
                               VehicleRepository vehicles, ClientRepository clients, AuditService audit,
                               InterventionRepository sorties, SavCaseRepository dossiers,
                               @Value("${app.forfaits.alert-scan-enabled:true}") boolean autoScan) {
        this.soldPlans = soldPlans;
        this.alerts = alerts;
        this.vehicles = vehicles;
        this.clients = clients;
        this.audit = audit;
        this.sorties = sorties;
        this.dossiers = dossiers;
        this.autoScan = autoScan;
    }

    // ------------------------------------------------------------------ le scan

    /** Passe périodique, si le tour automatique est laissé ouvert. */
    @Scheduled(fixedDelayString = "${app.forfaits.alert-scan-minutes:10}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void scheduledScan() {
        if (!autoScan) {
            return;
        }
        scanOnce();
    }

    /**
     * La même passe demandée depuis l'écran (« Vérifier maintenant ») : l'atelier
     * n'a pas à attendre le tour du scheduler pour savoir quels bus l'attendent.
     *
     * @return le nombre de rappels ouverts par cette passe
     */
    @Transactional
    public int scan() {
        return scanOnce();
    }

    private int scanOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<SoldPlan> actifs = soldPlans.findByStatus(SoldPlanStatus.ACTIVE);
        Map<String, Compteur> compteurs = new HashMap<>();
        int created = 0;

        for (SoldPlan sold : actifs) {
            Compteur compteur = compteurs.computeIfAbsent(sold.getVehicleId(), this::compteurConnu);
            for (SoldPlanStep suivi : sold.getSteps()) {
                Optional<Rappel> due = rappe(sold, suivi, compteur, now);
                if (due.isPresent()) {
                    if (!alerts.existsBySoldPlanStepIdAndResolvedFalse(suivi.getId())) {
                        alerts.save(nouvelleAlerte(sold.getId(), suivi.getId(), due.get(), now));
                        created++;
                    }
                }
                // Ce qui n'est plus vrai se ferme tout seul : le palier s'est fait,
                // le compteur a été corrigé. Personne n'a eu à décider, donc rien à écrire.
                if (due.isEmpty()) {
                    resoudre(suivi.getId(), now);
                }
            }
        }

        fermerCeQuiNeSePosePlus(actifs, now);
        return created;
    }

    /**
     * Les rappels ouverts qui ne relèvent plus d'aucun forfait actif se ferment :
     * un forfait clôturé ou annulé entre deux passes ne doit pas laisser son rappel
     * à l'écran, sinon la liste devient du bruit et plus personne ne la lit.
     */
    private void fermerCeQuiNeSePosePlus(List<SoldPlan> actifs, LocalDateTime now) {
        Map<String, String> lignesSuivies = new HashMap<>();
        for (SoldPlan sold : actifs) {
            for (SoldPlanStep suivi : sold.getSteps()) {
                lignesSuivies.put(suivi.getId(), sold.getId());
            }
        }
        for (SoldPlanAlert a : alerts.findByResolvedFalseOrderByDetectedAtAsc()) {
            if (!lignesSuivies.containsKey(a.getSoldPlanStepId())) {
                resoudre(a.getSoldPlanStepId(), now);
            }
        }
    }

    /** Tous les rappels ouverts d'un palier qui vient de se faire, ou de redevenir à faire. */
    private void resoudre(String soldPlanStepId, LocalDateTime now) {
        for (SoldPlanAlert a : alerts.findBySoldPlanStepIdAndResolvedFalse(soldPlanStepId)) {
            a.setResolved(true);
            a.setResolvedAt(now);
            alerts.save(a);
        }
    }

    // ------------------------------------------------------------------ la règle

    /** Le rappel qui se vérifie sur CE palier, avec le message qui sera stocké. */
    private static Optional<Rappel> rappe(SoldPlan sold, SoldPlanStep suivi, Compteur compteur,
                                          LocalDateTime now) {
        if (suivi.getStatus() != SoldPlanStepStatus.TODO) {
            return Optional.empty();
        }
        MaintenancePlanStep palier = suivi.getStep();
        Long seuilKm = palier.getKm();
        Integer jours = palier.getDays();
        LocalDate echeance = jours == null ? null : sold.getStartDate().plusDays(jours);
        boolean delaiEchu = echeance != null && !now.toLocalDate().isBefore(echeance);
        boolean compteurAtteint = seuilKm != null && compteur.km() != null && compteur.km() >= seuilKm;
        if (!compteurAtteint && !delaiEchu) {
            return Optional.empty();
        }

        String ouLePalier = "le palier « " + palier.getLabel() + " » (" + bornes(palier, sold.getStartDate())
                + ") du forfait « " + sold.getPlan().getName() + " »";
        if (compteurAtteint) {
            return Optional.of(new Rappel(SoldPlanAlertKind.KM_ATTEINT,
                    bus(sold.getVehicleId(), compteur) + " : compteur connu " + km(compteur.km()) + " km ("
                            + compteur.source() + "), " + ouLePalier + " atteint."
                            + (jours == null ? "" : " Délai de " + jours + " jours "
                                    + (delaiEchu ? "lui aussi écoulé."
                                            : "pas encore écoulé (échéance le " + echeance + ")."))));
        }
        return Optional.of(new Rappel(SoldPlanAlertKind.DELAI_ECHU,
                bus(sold.getVehicleId(), compteur) + " : " + jours + " jours échus depuis le début du forfait ("
                        + sold.getStartDate() + "), " + ouLePalier + " à faire"
                        + (seuilKm == null ? " (aucune borne en kilomètres)."
                                : ", alors que le compteur connu "
                                        + (compteur.km() == null ? "n'est pas saisi" : km(compteur.km()) + " km")
                                        + " n'a pas atteint " + km(seuilKm) + " km.")));
    }

    /**
     * Les deux bornes du palier, dans l'ordre où le catalogue les a écrites. Le
     * message d'un rappel doit se juger sans rouvrir la fiche du plan : « 40 000 km
     * ou 240 jours depuis le 2026-03-21 » se lit d'un coup d'œil à l'atelier.
     */
    private static String bornes(MaintenancePlanStep palier, LocalDate debutForfait) {
        String km = palier.getKm() == null ? null : km(palier.getKm()) + " km";
        String jours = palier.getDays() == null ? null
                : palier.getDays() + " jours depuis le " + debutForfait;
        if (km == null) {
            return jours == null ? "aucune borne" : jours;
        }
        return jours == null ? km : km + " ou " + jours;
    }

    // ------------------------------------------------------------------ le compteur

    /**
     * Le compteur du bus : la valeur de sa fiche, et rien d'autre. Les relevés des
     * sorties y sont déjà remontés par la porte unique du compteur ; les recompter
     * ici ferait deux chiffres pour un même bus dès que l'atelier remet volontairement
     * un compteur plus bas (compteur changé, relevé faux) — l'écran Véhicules dirait
     * 4 300 km pendant que ce bandeau réclamerait une visite des 10 000 km.
     *
     * La source est annoncée avec le chiffre : un rappel contesté se discute sur la
     * provenance du kilométrage, pas sur une valeur sans origine.
     */
    private Compteur compteurConnu(String vehicleId) {
        Vehicle bus = vehicles.findById(vehicleId).orElse(null);
        if (bus == null || bus.getMileage() == null) {
            return new Compteur(null, "aucun compteur relevé sur ce bus", bus);
        }
        return new Compteur(bus.getMileage(),
                bus.getMileageOrigin() == null
                        ? "saisi sur la fiche du bus" : bus.getMileageOrigin().label(),
                bus);
    }

    private static String bus(String vehicleId, Compteur compteur) {
        Vehicle bus = compteur.bus();
        if (bus == null) {
            return "Bus introuvable (" + vehicleId + ")";
        }
        String immat = bus.getRegistration() == null || bus.getRegistration().isBlank()
                ? "—" : bus.getRegistration();
        return "Bus " + immat + (bus.getVin() == null || bus.getVin().isBlank() ? "" : " — VIN " + bus.getVin());
    }

    // ------------------------------------------------------------------ lecture

    /**
     * Les rappels encore ouverts, tels que l'onglet Forfaits les lit : du plus
     * ancien au plus récent, avec le bus, le client, le palier et la règle évaluée.
     */
    @Transactional(readOnly = true)
    public PlanAlertListResponse ouverts() {
        List<SoldPlanAlert> rows = alerts.findByResolvedFalseOrderByDetectedAtAsc();
        Map<String, SoldPlan> forfaits = indexForfaits(rows);
        Map<String, Vehicle> bus = indexBus(forfaits.values());
        Map<String, String> clients = indexClients(forfaits.values());
        Map<String, String> sorties = indexSorties(rows);
        Map<String, SavCase> visites = indexDossiersVisites(rows);

        List<PlanAlertDto> dtos = new ArrayList<>();
        for (SoldPlanAlert a : rows) {
            SoldPlan sold = forfaits.get(a.getSoldPlanId());
            if (sold == null) {
                // Forfait effacé de la base : la question ne se pose plus.
                continue;
            }
            dtos.add(dto(a, sold, bus.get(sold.getVehicleId()), clients.get(sold.getClientId()),
                    compteurConnu(sold.getVehicleId()),
                    a.getInterventionId() == null ? null : sorties.get(a.getInterventionId()),
                    visiteDe(sold, a, visites)));
        }
        return new PlanAlertListResponse(dtos, dtos.size(), regles(), LocalDateTime.now());
    }

    /** Ce que le scan demande à un document de faire, pour un rappel donné. */
    private PlanAlertDto dto(SoldPlanAlert a, SoldPlan sold, Vehicle bus, String clientName, Compteur compteur,
                             String sortieNumber, SavCase visiteOuverte) {
        SoldPlanStep suivi = suiviDe(sold, a.getSoldPlanStepId());
        MaintenancePlanStep palier = suivi == null ? null : suivi.getStep();
        return new PlanAlertDto(a.getId(), sold.getId(), sold.getPlan().getName(),
                sold.getClientId(), clientName, sold.getVehicleId(),
                bus == null ? null : bus.getVin(), bus == null ? null : bus.getRegistration(),
                suivi == null ? null : palier.getId(),
                palier == null ? null : palier.getLabel(),
                palier == null ? null : palier.getKm(), palier == null ? null : palier.getDays(),
                sold.getStartDate(), compteur.km(), compteur.source(),
                a.getKind().name(), a.getKind().label(), a.getMessage(), a.getDetectedAt(),
                a.isWaived(), a.getWaiverReason(), a.getWaivedByEmail(), a.getWaivedAt(),
                a.getInterventionId(), sortieNumber,
                visiteOuverte == null ? null : visiteOuverte.getId(),
                visiteOuverte == null ? null : visiteOuverte.getCaseNumber());
    }

    /** La ligne de suivi d'un rappel : null si le palier a quitté le forfait depuis. */
    private static SoldPlanStep suiviDe(SoldPlan sold, String soldPlanStepId) {
        for (SoldPlanStep suivi : sold.getSteps()) {
            if (suivi.getId().equals(soldPlanStepId)) {
                return suivi;
            }
        }
        return null;
    }

    /** Les seuils appliqués, en français, récités au-dessus de la liste. */
    public String regles() {
        return "Règle appliquée : un palier encore à faire d'un forfait actif est rappelé dès que le compteur "
                + "du bus atteint sa borne en km, OU dès que son nombre de jours est écoulé depuis le "
                + "début du forfait — le premier des deux atteint décide. Compteur du bus = la valeur portée "
                + "par sa fiche, tenue par une seule écriture : le relevé du technicien sur une sortie la "
                + "fait monter, une baisse volontaire ne se fait que par écrit. Un rappel ne bloque et ne "
                + "facture rien ; il se lève par une raison écrite, et ce qui se règle tout seul se ferme. "
                + "Une ligne ouverte peut être prise en charge par une sortie d'entretien (un homme, une "
                + "voiture de déplacement, des heures au registre) : le rappel reste ouvert jusqu'au palier "
                + "fait, parce qu'une équipe en route n'est pas une visite accomplie.";
    }

    // ------------------------------------------------------------------ la décision

    /**
     * Lever un rappel : la seule écriture d'un humain sur une alerte. Rien
     * n'est effacé — la ligne garde le signal, la raison, son auteur et l'heure,
     * parce que c'est ce croisement qui se relit quand on demande pourquoi une
     * visite n'a pas été facturée.
     *
     * Lever n'empêche pas le palier d'être à faire : la ligne de suivi reste à TODO
     * et le rappel reviendra si le palier se fait attendre. Ce qui ferme un rappel
     * sans décision écrite, c'est le document qui fait le palier.
     */
    @Transactional
    public PlanAlertDto lever(String alertId, WaiveRequest request, Jwt jwt, Authentication auth) {
        SoldPlanAlert a = alerts.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rappel introuvable."));
        if (!Access.managesSoldPlans(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seul un compte qui écrit dans les forfaits (admin ou gestionnaire SAV) lève un rappel. "
                            + "Un autre compte le lit, il ne se donne pas raison à lui-même.");
        }
        if (a.isResolved()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce rappel est déjà fermé" + (a.getWaivedAt() != null
                            ? " : levé par " + a.getWaivedByEmail() + " le " + date(a.getWaivedAt())
                            : a.getResolvedAt() != null ? " le " + date(a.getResolvedAt()) : "")
                            + ". Une décision se prend une fois.");
        }
        String reason = trimToNull(request == null ? null : request.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un rappel ne se lève pas sans écrit : dites pourquoi ce palier est tenu pour non dû "
                            + "quand même (bus vendu, visite faite hors portail). Votre raison restera sur la "
                            + "ligne et se relira au contrôle.");
        }

        SoldPlan sold = soldPlans.findById(a.getSoldPlanId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Le forfait de ce rappel est introuvable : ouvrez sa fiche pour le traiter."));
        LocalDateTime now = LocalDateTime.now();
        a.setResolved(true);
        a.setResolvedAt(now);
        a.setWaived(true);
        a.setWaiverReason(truncate(reason, 500));
        a.setWaivedByEmail(jwt == null ? null : jwt.getClaimAsString("email"));
        a.setWaivedAt(now);
        alerts.save(a);

        SoldPlanStep suivi = suiviDe(sold, a.getSoldPlanStepId());
        audit.record("WAIVE", "SOLD_PLAN_ALERT", a.getId(),
                "Lever du rappel « " + a.getKind().label() + " » sur le palier « "
                        + (suivi == null ? a.getSoldPlanStepId() : suivi.getStep().getLabel())
                        + " » du forfait « " + sold.getPlan().getName() + " » : " + reason, jwt);
        return dto(a, sold, vehicles.findById(sold.getVehicleId()).orElse(null),
                clients.findById(sold.getClientId()).map(Client::getName).orElse(null),
                compteurConnu(sold.getVehicleId()), numeroDeSortie(a.getInterventionId()),
                visiteDe(sold, a, indexDossiersVisites(List.of(a))));
    }

    // ---------------------------------------------------- envoyer une équipe sur ce rappel

    /**
     * Ce que le rappel autorise à sortir : le bus du forfait, le palier visé, et la
     * sortie déjà envoyée s'il y en a une une.
     */
    public record EnvoiAutorise(String alertId, String soldPlanId, String planStepId, String palierLabel,
                                String planName, String vehicleId, String clientId, String messageRappel,
                                String interventionIdDejaEnvoyee) {}

    /**
     * La vérification qui précède l'envoi d'une sortie d'entretien. Elle ne touche
     * rien : elle dit seulement si la question posée par le rappel mérite encore une
     * réponse, et sur quoi la sortie doit s'écrire.
     *
     * Un rappel déjà fermé ne se rejoue pas (levé ou fait) ; un palier marqué fait
     * n'a plus personne à envoyer ; un forfait qui n'est plus ACTIF n'engage plus
     * l'atelier. Trois refus écrits, parce qu'un bouton grisé ne protège rien dès
     * qu'une requête est fabriquée à la main.
     */
    @Transactional(readOnly = true)
    public EnvoiAutorise rappelAEnvoyer(String alertId) {
        SoldPlanAlert a = alerts.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rappel introuvable."));
        if (a.isResolved()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce rappel est déjà fermé" + (a.isWaived() ? " : levé par " + a.getWaivedByEmail()
                            + " le " + date(a.getWaivedAt()) : " le " + date(a.getResolvedAt()))
                            + ". On n'envoie pas une équipe sur une question qui n'est plus posée.");
        }
        SoldPlan sold = soldPlans.findById(a.getSoldPlanId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Le forfait de ce rappel est introuvable : ouvrez sa fiche pour le traiter."));
        if (sold.getStatus() != SoldPlanStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le forfait « " + sold.getPlan().getName() + " » n'est plus actif : rien à faire sortir "
                            + "pour ses paliers.");
        }
        SoldPlanStep suivi = suiviDe(sold, a.getSoldPlanStepId());
        if (suivi == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le palier de ce rappel n'appartient plus au forfait « " + sold.getPlan().getName()
                            + " » : le scan reposera la question sur la ligne qui tient aujourd'hui.");
        }
        if (suivi.getStatus() == SoldPlanStepStatus.DONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le palier « " + suivi.getStep().getLabel() + " » est déjà marqué fait. Une même visite "
                            + "ne se fait pas deux fois : rouvrez le document qui l'a portée si c'est une erreur.");
        }
        return new EnvoiAutorise(a.getId(), sold.getId(), suivi.getStep().getId(),
                suivi.getStep().getLabel(), sold.getPlan().getName(), sold.getVehicleId(),
                sold.getClientId(), a.getMessage(), a.getInterventionId());
    }

    /**
     * Le rappel est pris en charge par une sortie : la ligne le dit, elle reste
     * ouverte. Une équipe qui roule n'est pas une visite faite — si le technicien
     * rentre sans avoir coché le palier, l'atelier doit retrouver la question.
     */
    @Transactional
    public void priseEnCharge(String alertId, String interventionId, String sortieNumber, Jwt jwt) {
        SoldPlanAlert a = alerts.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rappel introuvable."));
        a.setInterventionId(interventionId);
        alerts.save(a);
        audit.record("ENVOI", "SOLD_PLAN_ALERT", alertId,
                "Rappel « " + a.getKind().label() + " » pris en charge par la sortie " + sortieNumber, jwt);
    }

    // -------------------------------------------------- ce qui ferme un rappel sans décision

    /**
     * Le palier vient de se faire (document livré) : son rappel se ferme
     * immédiatement, sans attendre la passe périodique. C'est le seul endroit où
     * l'écran pourrait se contredire tout seul — le suivi dirait « fait » pendant
     * que le bandeau dirait « à faire ».
     */
    @Transactional
    public void palierFait(String soldPlanStepId) {
        resoudre(soldPlanStepId, LocalDateTime.now());
    }

    /**
     * Le forfait sort d'ACTIF (clôturé ou annulé) : ses rappels ouverts se ferment,
     * parce qu'un engagement terminé ne se rappelle plus.
     */
    @Transactional
    public void forfaitFerme(String soldPlanId) {
        for (SoldPlanAlert a : alerts.findBySoldPlanIdOrderByDetectedAtDesc(soldPlanId)) {
            if (!a.isResolved()) {
                a.setResolved(true);
                a.setResolvedAt(LocalDateTime.now());
                alerts.save(a);
            }
        }
    }

    // ------------------------------------------------------------------ indexages

    private Map<String, SoldPlan> indexForfaits(List<SoldPlanAlert> rows) {
        List<String> ids = rows.stream().map(SoldPlanAlert::getSoldPlanId).distinct().toList();
        Map<String, SoldPlan> map = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            soldPlans.findAllById(ids).forEach(s -> map.put(s.getId(), s));
        }
        return map;
    }

    private Map<String, Vehicle> indexBus(Iterable<SoldPlan> forfaits) {
        List<String> ids = new ArrayList<>();
        forfaits.forEach(s -> {
            if (s.getVehicleId() != null && !ids.contains(s.getVehicleId())) {
                ids.add(s.getVehicleId());
            }
        });
        Map<String, Vehicle> map = new HashMap<>();
        vehicles.findAllById(ids).forEach(v -> map.put(v.getId(), v));
        return map;
    }

    private Map<String, String> indexClients(Iterable<SoldPlan> forfaits) {
        List<String> ids = new ArrayList<>();
        forfaits.forEach(s -> {
            if (s.getClientId() != null && !ids.contains(s.getClientId())) {
                ids.add(s.getClientId());
            }
        });
        Map<String, String> map = new HashMap<>();
        clients.findAllById(ids).forEach(c -> map.put(c.getId(), c.getName()));
        return map;
    }

    /** Les numéros des sorties déjà envoyées sur des rappels, en une requête. */
    private Map<String, String> indexSorties(List<SoldPlanAlert> rows) {
        List<String> ids = rows.stream().map(SoldPlanAlert::getInterventionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, String> map = new HashMap<>();
        if (!ids.isEmpty()) {
            sorties.findAllById(ids).forEach(s -> map.put(s.getId(), s.getNumber()));
        }
        return map;
    }

    private String numeroDeSortie(String interventionId) {
        return interventionId == null ? null
                : sorties.findById(interventionId).map(Intervention::getNumber).orElse(null);
    }

    /**
     * Les dossiers de visite déjà ouverts sur les paliers rappelés, indexés sous
     * « forfait | palier du catalogue ». Une seule requête pour toute la liste : la
     * question se pose ligne par ligne à l'écran, et la reposeraient vingt fois
     * enverraient vingt requêtes sur un bandeau de vingt rappels.
     *
     * Un dossier clôturé ne répond plus : il a été rendu, son palier se lit ailleurs.
     */
    private Map<String, SavCase> indexDossiersVisites(List<SoldPlanAlert> rows) {
        List<String> ids = rows.stream().map(SoldPlanAlert::getSoldPlanId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, SavCase> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (SavCase c : dossiers.findBySoldPlanIdInOrderByCreatedAtDesc(ids)) {
            if (c.getType() != SavCaseType.VISITES || c.getStatus() == SavCaseStatus.CLOSED
                    || c.getSoldPlanId() == null || c.getPlanStepId() == null) {
                continue;
            }
            map.putIfAbsent(cleVisite(c.getSoldPlanId(), c.getPlanStepId()), c);
        }
        return map;
    }

    /** Le dossier de visite qui répond déjà à ce rappel, s'il y en a un. */
    private SavCase visiteDe(SoldPlan sold, SoldPlanAlert a, Map<String, SavCase> visites) {
        SoldPlanStep suivi = suiviDe(sold, a.getSoldPlanStepId());
        if (suivi == null || suivi.getStep() == null) {
            return null;
        }
        return visites.get(cleVisite(sold.getId(), suivi.getStep().getId()));
    }

    /** Le palier se nomme par son identifiant du catalogue, jamais par celui du suivi. */
    private static String cleVisite(String soldPlanId, String stepId) {
        return soldPlanId + "|" + stepId;
    }

    // ------------------------------------------------------------------ outils

    private static SoldPlanAlert nouvelleAlerte(String soldPlanId, String soldPlanStepId, Rappel rappel,
                                                LocalDateTime now) {
        SoldPlanAlert a = new SoldPlanAlert();
        a.setId("spa-" + UUID.randomUUID());
        a.setSoldPlanId(soldPlanId);
        a.setSoldPlanStepId(soldPlanStepId);
        a.setKind(rappel.kind());
        a.setMessage(truncate(rappel.message(), 500));
        a.setDetectedAt(now);
        a.setResolved(false);
        a.setWaived(false);
        return a;
    }

    /** 45000 se lit « 45 000 » : l'atelier compare des chiffres ronds, pas des id. */
    private static String km(Long value) {
        if (value == null) {
            return "—";
        }
        String digits = String.valueOf(value);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    private static String date(LocalDateTime value) {
        return value == null ? "—" : String.valueOf(value.toLocalDate());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Le compteur connu d'un bus, avec l'origine du chiffre et la fiche du bus. */
    private record Compteur(Long km, String source, Vehicle bus) {}

    /** Une règle qui s'est vérifiée : de quoi elle parle, et ce qui s'écrira sur la ligne. */
    private record Rappel(SoldPlanAlertKind kind, String message) {}
}
