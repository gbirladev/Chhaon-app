package org.smartgurukul.chhaon.recipient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * A doctor or emergency contact, stored inside the jsonb arrays
 * {@code doctor_contacts} / {@code emergency_contacts} as {@code {"name":..,"phone":..}}.
 * A doctor ("Your doctors", 2026-11-06) may also have a speciality, a clinic or hospital and an address; those keys are
 * left out of the json when empty, so an emergency contact is stored exactly as before.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Contact {
    private String name;
    private String phone;
    private String speciality;
    private String clinic;
    private String address;

    public Contact() {}

    public Contact(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    public Contact(String name, String phone, String speciality, String clinic, String address) {
        this.name = name;
        this.phone = phone;
        this.speciality = speciality;
        this.clinic = clinic;
        this.address = address;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getSpeciality() { return speciality; }
    public void setSpeciality(String speciality) { this.speciality = speciality; }
    public String getClinic() { return clinic; }
    public void setClinic(String clinic) { this.clinic = clinic; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Contact c)) return false;
        return Objects.equals(name, c.name) && Objects.equals(phone, c.phone) && Objects.equals(speciality, c.speciality)
                && Objects.equals(clinic, c.clinic) && Objects.equals(address, c.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, phone, speciality, clinic, address);
    }
}
