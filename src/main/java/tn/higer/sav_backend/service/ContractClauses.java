package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.Contract;

/**
 * Rédaction standard des six clauses d'un contrat de maintenance.
 *
 * <p>Le contrat que le portail rédige est un <strong>contrat d'entretien</strong> : Saphir
 * s'engage à maintenir les bus que le client lui confie, il ne vend pas les véhicules —
 * la vente se traite ailleurs, avec Higer. Les six colonnes du contrat (objet, delivery,
 * warranty, ownership, dispute, special) portent donc les six articles d'un contrat de
 * maintenance, dans cet ordre à l'impression.</p>
 *
 * <p>Chaque clause reste un texte libre stocké sur {@link Contract}. Quand la zone est vide
 * (null), le contrat imprimable retombe sur la rédaction standard produite ici — jamais sur
 * une clause inventée. Les chiffres du standard (délai, garantie) viennent de la configuration
 * {@code app.contract.*}, pas d'un modèle : c'est ce qui rend le document imprimable sans
 * relecture comptable. Une IA (Groq) peut proposer un texte, mais uniquement après validation
 * humaine ; le texte standard reste la valeur de repli.</p>
 */
@Component
public class ContractClauses {

    private final int deliveryDays;
    private final int warrantyMonths;
    private final int warrantyKm;
    private final String tribunal;
    private final String vendorName;

    public ContractClauses(@Value("${app.contract.delivery-days:60}") int deliveryDays,
                           @Value("${app.contract.warranty-months:24}") int warrantyMonths,
                           @Value("${app.contract.warranty-km:100000}") int warrantyKm,
                           @Value("${app.contract.tribunal:Tribunal de Commerce de Tunis}") String tribunal,
                           @Value("${app.vendor.name}") String vendorName) {
        this.deliveryDays = deliveryDays;
        this.warrantyMonths = warrantyMonths;
        this.warrantyKm = warrantyKm;
        this.tribunal = tribunal;
        this.vendorName = vendorName;
    }

    /**
     * Texte effectif d'une clause : la saisie si elle existe, sinon le standard.
     * Blanc = absent (même traitement que null), pour que vider une zone rétablisse le standard.
     */
    public static String effective(String entered, String standard) {
        return entered == null || entered.isBlank() ? standard : entered.trim();
    }

    /** Article 1 — Objet (colonne {@code object_clause}). */
    public String objectStandard(String clientName, int vehicleCount) {
        // Un seul nommage, un seul nombre : « de le » et « de les » sont des fautes, le contrat
        // est signé tel quel.
        String vehicles = vehicleCount <= 1
                ? "du véhicule désigné à l'annexe"
                : "des " + vehicleCount + " véhicules désignés à l'annexe";
        return "Le présent contrat a pour objet la maintenance préventive et corrective " + vehicles
                + ", assurée par " + vendorName + " pour " + clientName + ", qui lui confie "
                + (vehicleCount <= 1 ? "ce véhicule" : "ces véhicules")
                + " aux conditions rappelées en annexe et aux conditions particulières.";
    }

    /** Article 2 — Prestations et délais (colonne {@code delivery_clause}, le mot du contrat reste « delivery » en base). */
    public String deliveryStandard() {
        return "Les prestations comprennent les visites périodiques prévues au carnet d'entretien, "
                + "le diagnostic, les réparations et le remplacement des pièces, exécutés en atelier ou "
                + "sur site. Pour les travaux confiés en atelier, " + vendorName + " restitue le véhicule "
                + "dans un délai de " + deliveryDays + " jours ouvrables à compter de sa mise à disposition ; "
                + "une intervention sur site est déclenchée à l'appel du client et consignée sous forme de "
                + "sortie de dépannage.";
    }

    /** Article 3 — Garantie des travaux (colonne {@code warranty_clause}). */
    public String warrantyStandard() {
        return "Les travaux exécutés par " + vendorName + " sont garantis " + warrantyMonths + " mois "
                + "à compter de la date de restitution du véhicule, dans la limite de " // Locale figé : le document ne dépend pas de la JVM
                + String.format(java.util.Locale.US, "%,d", warrantyKm).replace(',', ' ')
                + " kilomètres parcourus. Les pièces d'origine neuves restent couvertes par la garantie "
                + "constructeur selon le carnet d'entretien ; l'usure normale et le fait d'un tiers "
                + "n'ouvrent pas la garantie des travaux.";
    }

    /** Article 4 — Pièces déposées et propriété (colonne {@code ownership_clause}). */
    public String ownershipStandard() {
        return "Le client reste propriétaire des véhicules qu'il confie. Les pièces déposées lors d'un "
                + "remplacement lui sont restituées sur demande écrite, sauf lorsqu'elles doivent être "
                + "retournées à " + vendorName + " au titre de la garantie constructeur ; à défaut de "
                + "demande, elles sont neutralisées et tracées dans le dossier de l'intervention.";
    }

    /** Article 5 — Litiges (colonne {@code dispute_clause}). */
    public String disputeStandard() {
        return "Tout litige relatif à la formation, à l'exécution ou à l'interprétation du présent contrat, "
                + "à défaut d'accord amiable, sera soumis à la compétence exclusive du " + tribunal + ".";
    }

    /** Article 6 — Clauses particulières : aucun standard, vide signifie « rien d'ajouté ». */
    public String specialStandard() {
        return "";
    }

    // Accesseurs de configuration, réutilisés par le rendu pour les mentions du pied de page.
    public String vendorName() {
        return vendorName;
    }

    public String tribunal() {
        return tribunal;
    }
}
