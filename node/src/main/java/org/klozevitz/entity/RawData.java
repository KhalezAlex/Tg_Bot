package org.klozevitz.entity;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.telegram.telegrambots.meta.api.objects.Update;

import javax.persistence.*;

/**
 * @EqualsAndHashCode - we should override equals() and hashCode() methods excluding "id" field in order
 * to get positive equality between persistent and transient objects that are potentially equal
 * */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "id")
@Builder
@Entity
@Table(name = "raw_data")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class RawData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Update event;
}
