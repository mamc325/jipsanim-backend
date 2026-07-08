package com.jipsanim.user.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "realtor", uniqueConstraints = @UniqueConstraint(name = "uk_realtor_user", columnNames = "user_id"))
public class Realtor extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "business_name", nullable = false, length = 100)
    private String businessName;

    @Column(length = 30)
    private String phone;

    protected Realtor() {
    }

    private Realtor(User user, String businessName, String phone) {
        this.user = user;
        this.businessName = businessName;
        this.phone = phone;
    }

    public static Realtor create(User user, String businessName, String phone) {
        return new Realtor(user, businessName, phone);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getPhone() {
        return phone;
    }
}
