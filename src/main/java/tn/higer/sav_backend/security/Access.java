package tn.higer.sav_backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Droits de lecture des informations commerciales (prix, CA, conditions de paiement).
 * Le rôle consultation lit tout, sauf l'argent : le masquage est fait ici, côté serveur,
 * pour que la réponse JSON ne contienne pas la valeur — un simple « hidden » dans
 * l'interface ne serait pas une protection.
 */
public final class Access {

    private Access() {}

    public static boolean seesCommercialFigures(Authentication auth) {
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_GESTIONNAIRE_SAV"));
    }

    /**
     * Ce que Saphir PAYE aux techniciens (taux horaire, forfait de sortie) :
     * ADMIN et CHEF_ATELIER uniquement. Le gestionnaire SAV lit les coûts
     * commerciaux, pas la paie — les deux listes sont distinctes volontairement,
     * sinon une prime finirait par être calculée au prix de vente.
     */
    public static boolean seesPayroll(Authentication auth) {
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_CHEF_ATELIER"));
    }

    /**
     * Un compte de technicien ne voit que ses propres sorties, et n'a accès à
     * aucun montant. Le test est fait serveur : un écran qui « grise » le
     * bouton ne protège rien si la requête, elle, passe.
     */
    public static boolean isTechnician(Authentication auth) {
        return has(auth, "ROLE_TECHNICIEN");
    }

    /** Chef d'atelier ou ADMIN : seuls eux affectent, clôturent et rectifient une heure. */
    public static boolean managesInterventions(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_CHEF_ATELIER");
    }

    /**
     * Ce qui écrit dans les forfaits — catalogue et forfaits vendus : ADMIN et
     * gestionnaire SAV, les deux seuls rôles que la règle générique de
     * {@code SecurityConfig} laisse déjà POSTER sur {@code /api/**}. Le même test
     * sert à lever un rappel de palier : un compte qui ne peut pas vendre un forfait
     * ne doit pas décider qu'un de ses paliers n'est pas dû.
     */
    public static boolean managesSoldPlans(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_GESTIONNAIRE_SAV");
    }

    /**
     * Accès au module Dépannage : ADMIN, chef d'atelier, gestionnaire SAV et
     * technicien. Le rôle CONSULTATION en est exclu — le contrôle des sorties est
     * interne, et une fiche client qui lit le portail ne doit pas voir qui est
     * parti, combien de temps, ni ce que ça coûte.
     */
    public static boolean usesDepannageModule(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_CHEF_ATELIER")
                || has(auth, "ROLE_GESTIONNAIRE_SAV") || has(auth, "ROLE_TECHNICIEN");
    }

    /**
     * Enregistrer une demande d'intervention (écran Missions) : le guichet et
     * l'atelier. Le compte de technicien n'y a pas affaire — il reçoit ce qui est
     * décidé dehors, il ne décide pas. Le rôle CONSULTATION est exclu, comme pour le
     * reste du module.
     */
    public static boolean recordsMissions(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_GESTIONNAIRE_SAV")
                || has(auth, "ROLE_CHEF_ATELIER");
    }

    /**
     * Mission groupe — préparer le dossier : admin, gestionnaire SAV et chef
     * d'atelier. Le regard du client (consultation) n'y entre pas, comme pour
     * Missions. Le technicien n'écrit pas le dossier : il le vit, une fois
     * affecté (voir {@link #readsMissionGroupe}).
     */
    public static boolean usesMissionGroupe(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_GESTIONNAIRE_SAV")
                || has(auth, "ROLE_CHEF_ATELIER");
    }

    /**
     * Lire la mission groupe : l'atelier et le technicien affecté. Le service
     * vérifie ensuite que le technicien ne lit que les dossiers où il est
     * l'homme désigné — cette règle-là a besoin du dossier, pas du seul rôle.
     */
    public static boolean readsMissionGroupe(Authentication auth) {
        return usesMissionGroupe(auth) || isTechnician(auth);
    }

    /**
     * Passer la commande d'une pièce et la réceptionner : le guichet (admin,
     * gestionnaire SAV) et l'atelier. Le compte de technicien en est exclu — il
     * demande la pièce devant le bus, il ne peut pas s'écrire qu'elle est commandée
     * ni qu'elle est arrivée. Les deux cases portent une date et un nom : si le même
     * homme les remplit, le contrôle n'a plus rien à contrôler.
     */
    public static boolean ordersAndReceivesParts(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_GESTIONNAIRE_SAV")
                || has(auth, "ROLE_CHEF_ATELIER");
    }

    /**
     * Trancher une mission — envoyer un homme, ouvrir un ordre, refuser, reporter :
     * les mêmes que pour une sortie. Qui a le droit de faire sortir un homme et une
     * voiture reste une question à trancher par l'atelier ; le défaut pris est
     * réversible et suit la garde déjà en place sur la sortie.
     */
    public static boolean decidesMissions(Authentication auth) {
        return managesInterventions(auth);
    }

    /**
     * Les fiches du portefeuille : la liste des bus et celle des clients.
     *
     * Trois rôles en ont besoin pour travailler : le guichet (admin, gestionnaire
     * SAV) qui saisit un dossier sur un bus et engage un client, l'atelier qui
     * affecte une voiture à une sortie. Les deux autres en sont dehors, et pour des
     * raisons différentes :
     *   — le compte de technicien sort sur une affectation. Le bus lui est désigné,
     *     son écran relit ce qu'on lui a donné ; parcourir le parc entier ne lui
     *     sert rien, et lui donner la liste serait lui donner les clients des autres ;
     *   — le rôle CONSULTATION est le regard du client posé sur SES dossiers. La
     *     fiche des bus de Saphir et l'annuaire des clients ne sont pas les siennes :
     *     un seul client qui consulte verrait le nom, l'immatriculation, le
     *     kilométrage et la garantie de tous les autres.
     *
     * Le test est ici et pas dans un menu caché : la liste se lit par requête, et
     * une requête se fabrique à la main.
     */
    public static boolean readsFleetAndClients(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_GESTIONNAIRE_SAV")
                || has(auth, "ROLE_CHEF_ATELIER");
    }

    /**
     * Relire le dossier d'appel en garantie avec ses justificatifs — les sorties qui
     * y répondent et les pièces posées que le constructeur va vouloir récupérer.
     *
     * Le guichet (admin, gestionnaire SAV) et l'atelier. Ce papier nomme des sorties,
     * donc il tombe sous la règle du module Dépannage : un compte consultation — le
     * regard du client sur le portail — ne doit pas voir qui est parti chez lui ni ce
     * qui a été monté sur son bus. Le compte de technicien n'y a pas affaire : il
     * répare, il n'assemble pas la demande de remboursement.
     */
    public static boolean readsClaimPapers(Authentication auth) {
        return has(auth, "ROLE_ADMIN") || has(auth, "ROLE_GESTIONNAIRE_SAV")
                || has(auth, "ROLE_CHEF_ATELIER");
    }

    /**
     * ADMIN seul. Sert aux actes rares et nommables : toucher la preuve d'une
     * sortie déjà clôtée, par exemple. Le journal doit pouvoir dire de qui.
     */
    public static boolean isAdmin(Authentication auth) {
        return has(auth, "ROLE_ADMIN");
    }

    private static boolean has(Authentication auth, String authority) {
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }
}
