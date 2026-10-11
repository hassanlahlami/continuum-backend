/**
 * API publique du module {@code iam} (plan §4.3.1, §2.1) : exposée aux autres modules comme interface
 * nommée Spring Modulith. Moteur {@code can()} ({@link com.continuum.iam.api.AuthorizationService}),
 * SoD ({@link com.continuum.iam.api.SegregationOfDutiesService}), affectations et référentiels de droits.
 * Les autres modules dépendent d'ici, jamais des entités/tables ni du paquet {@code internal}.
 */
@org.springframework.modulith.NamedInterface("api")
package com.continuum.iam.api;
