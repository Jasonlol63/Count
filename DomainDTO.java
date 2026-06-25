package com.eazycount.dto;

import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DomainDTO {
    private Owner owner;
    private Tenant tenant;
    /** Parent group business code from query join; not a tenant table column. */
    private String parentGroupCode;
    private List<Tenant> groups = new ArrayList<>();
    private List<Tenant> companies = new ArrayList<>();
}
