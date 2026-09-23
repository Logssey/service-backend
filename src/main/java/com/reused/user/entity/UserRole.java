package com.reused.user.entity;

/**
 * 역할은 USER와 ADMIN 두 가지뿐이다(ADR-006). 판매자는 별도 역할이 아니다.
 */
public enum UserRole {
	USER,
	ADMIN
}
