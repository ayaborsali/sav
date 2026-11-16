package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.ClientType;
import tn.higer.sav_backend.model.CommercialStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class ClientDtos {

    private ClientDtos() {}

    /**
     * Création / modification d'un client (admin et gestionnaire_sav).
     * type arrive en minuscules depuis Angular (« public_transport ») —
     * accept-case-insensitive-enums est activé dans application.properties.
     * Il en va de même pour commercialStatus (« prospect », « active », « lost »).
     */
    public record ClientRequest(@NotBlank String name,
                                @NotBlank String code,
                                @NotNull ClientType type,
                                String contactPerson,
                                String email,
                                String phone,
                                String address,
                                String city,
                                LocalDate createdAt,
                                @Size(max = 1000) String notes,
                                @Size(max = 120) String contactRole,
                                @Size(max = 80) String country,
                                LocalDate lastContactAt,
                                @Size(max = 120) String salesRep,
                                CommercialStatus commercialStatus,
                                @Size(max = 500) String products,
                                @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal annualRevenue,
                                @Size(max = 120) String paymentTerms,
                                @Size(max = 60) String tradeRegister,
                                @Size(max = 40) String taxId) {}

    /**
     * Réponse envoyée à l'interface. annualRevenue et paymentTerms sont mis à null
     * pour le rôle consultation : la confidentialité ne dépend pas de l'interface.
     */
    public record ClientResponse(String id, String name, String code, String type,
                                 String contactPerson, String email, String phone,
                                 String address, String city, LocalDate createdAt, String notes,
                                 String contactRole, String country, LocalDate lastContactAt,
                                 String salesRep, String commercialStatus, String products,
                                 BigDecimal annualRevenue, String paymentTerms,
                                 String tradeRegister, String taxId) {

        public static ClientResponse from(Client c) {
            return from(c, true);
        }

        public static ClientResponse from(Client c, boolean includeCommercial) {
            return new ClientResponse(
                    c.getId(), c.getName(), c.getCode(),
                    c.getType() != null ? c.getType().name().toLowerCase() : null,
                    c.getContactPerson(), c.getEmail(), c.getPhone(),
                    c.getAddress(), c.getCity(), c.getCreatedAt(), c.getNotes(),
                    c.getContactRole(), c.getCountry(), c.getLastContactAt(),
                    c.getSalesRep(),
                    c.getCommercialStatus() != null ? c.getCommercialStatus().name().toLowerCase() : null,
                    c.getProducts(),
                    includeCommercial ? c.getAnnualRevenue() : null,
                    includeCommercial ? c.getPaymentTerms() : null,
                    // RC et matricule fiscal : identité juridique, nécessaire au contrat pour tous les rôles.
                    c.getTradeRegister(), c.getTaxId());
        }
    }
}
