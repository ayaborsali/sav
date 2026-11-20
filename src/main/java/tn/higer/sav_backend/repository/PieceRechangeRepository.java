package tn.higer.sav_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.higer.sav_backend.model.PieceFamily;
import tn.higer.sav_backend.model.PieceRechange;

import java.util.Optional;

public interface PieceRechangeRepository extends JpaRepository<PieceRechange, String> {

    /**
     * Les quatre colonnes qu'une recherche d'atelier traverse : la référence Higer,
     * la référence du fournisseur — un client arrive parfois avec le papier d'un
     * concurrent, où seul ce numéro figure — et les deux désignations. Une seule
     * écriture de ce motif, parce que le filtre par famille doit chercher exactement
     * au même endroit que le filtre sans famille — deux versions qui divergent font
     * une famille qui « n'a rien » alors que le mot s'y trouve.
     */
    String MOTIFS = "lower(p.reference) like :pattern escape '\\' "
            + "or lower(coalesce(p.referenceFournisseur, '')) like :pattern escape '\\' "
            + "or lower(p.designationFr) like :pattern escape '\\' "
            + "or lower(coalesce(p.designationEn, '')) like :pattern escape '\\'";

    /**
     * Le catalogue, cherché par référence ou par désignation.
     *
     * La recherche est serveur et non pas « on charge tout et on filtre à l'écran »
     * comme ailleurs dans le portail : un catalogue de pièces Higer grossit ligne à
     * ligne, et surtout le futur devis appellera la même route pour son
     * autocomplétion. Une recherche qui ne passerait pas par le serveur ne pourrait
     * pas servir les deux.
     *
     * Le motif arrive déjà échappé (les {@code %} et {@code _} tapés par l'atelier
     * sont des caractères, pas des jokers) et minuscule : « Filtre à gasoil » et
     * « FILTRE A GASOIL » doivent trouver la même pièce.
     */
    @Query("select p from PieceRechange p where " + MOTIFS)
    Page<PieceRechange> search(@Param("pattern") String pattern, Pageable pageable);

    /**
     * La même recherche, dans une seule famille. Deux requêtes plutôt qu'un
     * {@code :famille is null or …} : le paramètre nullable d'un enum ne se rend pas
     * de façon stable en SQL, et un catalogue qui répond « rien » parce que le typage
     * a capoté est un catalogue qu'on croit vide.
     */
    @Query("select p from PieceRechange p where p.famille = :famille and (" + MOTIFS + ")")
    Page<PieceRechange> searchInFamille(@Param("famille") PieceFamily famille,
                                        @Param("pattern") String pattern, Pageable pageable);

    /** La référence est-elle déjà au catalogue, écrite fût-ce dans une autre casse ? */
    boolean existsByReferenceIgnoreCase(String reference);

    boolean existsByReferenceIgnoreCaseAndIdNot(String reference, String id);

    /** Une référence saisie en majuscules sur le classeur doit retrouver la pièce entrée en minuscules. */
    Optional<PieceRechange> findByReferenceIgnoreCase(String reference);
}
