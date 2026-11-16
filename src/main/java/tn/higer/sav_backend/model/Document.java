package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "documents")
@Getter @Setter @NoArgsConstructor
public class Document {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private DocumentCategory category;

    private String fileType;
    private String fileSize;

    private String vehicleId;
    private String clientId;

    private LocalDate uploadedAt;
    private String uploadedBy;

    @Column(columnDefinition = "TEXT")
    private String fileData;
}
