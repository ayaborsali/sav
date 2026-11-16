package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartRequest;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartResponse;
import tn.higer.sav_backend.dto.ItemPartDtos.RetourRequest;
import tn.higer.sav_backend.service.ItemPartService;

import java.util.List;

/**
 * Les pièces posées sur un bus, et ce qu'elles deviennent.
 *
 * Trois entrées, et une seule écriture par fait :
 *  - le papier de maintenance ajoute ses pièces (c'est lui qui sait dans quel bus
 *    elles sont montées — le bus n'est jamais redemandé à l'écran) ;
 *  - la pièce dépose son sort chez le constructeur (envoyée, ou pas envoyée avec
 *    la raison écrite) ;
 *  - le bus et le dossier garantie relisent ce qui les concerne.
 */
@RestController
@RequestMapping("/api")
public class ItemPartController {

    private final ItemPartService parts;

    public ItemPartController(ItemPartService parts) {
        this.parts = parts;
    }

    @GetMapping("/maintenance-items/{itemId}/parts")
    public List<ItemPartResponse> pourDocument(@PathVariable String itemId) {
        return parts.pourDocument(itemId);
    }

    @PostMapping("/maintenance-items/{itemId}/parts")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemPartResponse ajouter(@PathVariable String itemId,
                                    @Valid @RequestBody ItemPartRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        return parts.ajouter(itemId, request, jwt);
    }

    /** Le sort de la pièce déposée : envoyée, ou pas envoyée — avec sa preuve. */
    @PutMapping("/item-parts/{partId}/retour")
    public ItemPartResponse retour(@PathVariable String partId,
                                   @Valid @RequestBody RetourRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return parts.enregistrerRetour(partId, request, jwt);
    }

    @DeleteMapping("/item-parts/{partId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable String partId, @AuthenticationPrincipal Jwt jwt) {
        parts.supprimer(partId, jwt);
    }

    /** Les pièces posées sur ce bus — la fiche du bus doit pouvoir les montrer. */
    @GetMapping("/vehicles/{vehicleId}/parts")
    public List<ItemPartResponse> pourBus(@PathVariable String vehicleId) {
        return parts.pourBus(vehicleId);
    }
}
