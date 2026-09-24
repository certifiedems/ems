package com.ems.service.impl;

import java.util.Objects;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.dto.request.UpdateUserProfileRequest;
import com.ems.dto.request.UserRegistrationRequest;
import com.ems.dto.response.UserProfileResponse;
import com.ems.entity.Role;
import com.ems.entity.User;
import com.ems.enums.RoleName;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.RoleRepository;
import com.ems.repository.UserRepository;
import com.ems.security.PasswordPolicyValidator;
import com.ems.service.ProfilePhotoContent;
import com.ems.service.ProfilePhotoStorageService;
import com.ems.service.UserProfileService;
import com.ems.util.UserIdGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class UserProfileServiceImpl implements UserProfileService {

	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicyValidator passwordPolicyValidator;
	private final ProfilePhotoStorageService profilePhotoStorageService;
	private final UserIdGenerator userIdGenerator;

	@Override
	public UserProfileResponse register(UserRegistrationRequest request) {
		validateRegistration(request);
		passwordPolicyValidator.validateOrThrow(request.getPassword());

		Role userRole = roleRepository.findByName(RoleName.USER)
				.orElseThrow(() -> new ResourceNotFoundException("Default USER role is missing"));

		String userId = userIdGenerator.generate(
				request.getFirstName(),
				request.getLastName(),
				request.getEmail());

		String profilePhotoKey = profilePhotoStorageService.storeProfilePhoto(
				request.getProfilePhoto(),
				userId);

		User user = User.builder()
				.userId(userId)
				.firstName(request.getFirstName().trim())
				.lastName(request.getLastName().trim())
				.email(request.getEmail().trim().toLowerCase())
				.mobileNumber(request.getMobileNumber().trim())
				.passwordHash(passwordEncoder.encode(request.getPassword()))
				.profilePhotoKey(profilePhotoKey)
				.address(request.getAddress())
				.yearsOfExperience(request.getYearsOfExperience())
				.currentSkillLevel(request.getCurrentSkillLevel())
				.currentOrganization(request.getCurrentOrganization())
				.qualification(request.getQualification())
				.fatherName(request.getFatherName())
				.enabled(true)
				.accountNonLocked(true)
				.roles(Set.of(userRole))
				.build();

		User savedUser = userRepository.save(user);
		log.info("User registered through profile module: userId={}, email={}",
				savedUser.getUserId(), savedUser.getEmail());
		return toResponse(savedUser);
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileResponse getCurrentUserProfile(String email) {
		return toResponse(findByEmail(email));
	}

	@Override
	public UserProfileResponse updateCurrentUserProfile(String email, UpdateUserProfileRequest request) {
		User user = findByEmail(email);

		if (request.yearsOfExperience() != null && request.yearsOfExperience() < 0) {
			throw new BusinessException("Years of experience cannot be negative", HttpStatus.BAD_REQUEST);
		}

		if (request.mobileNumber() != null) {
			String normalizedMobile = request.mobileNumber().trim();
			if (!Objects.equals(normalizedMobile, user.getMobileNumber())
					&& userRepository.existsByMobileNumber(normalizedMobile)) {
				throw new BusinessException("Mobile number already exists", HttpStatus.CONFLICT);
			}
			user.setMobileNumber(normalizedMobile);
		}

		user.setFirstName(request.firstName().trim());
		user.setLastName(request.lastName().trim());
		user.setAddress(request.address());
		user.setYearsOfExperience(request.yearsOfExperience());
		user.setCurrentSkillLevel(request.currentSkillLevel());
		user.setCurrentOrganization(request.currentOrganization());
		user.setQualification(request.qualification());
		user.setFatherName(request.fatherName());

		if (request.profilePhoto() != null && !request.profilePhoto().isBlank()) {
			replaceProfilePhoto(user, request.profilePhoto());
		}

		return toResponse(userRepository.save(user));
	}

	@Override
	public UserProfileResponse uploadProfilePhoto(String email, String profilePhoto) {
		if (profilePhoto == null || profilePhoto.isBlank()) {
			throw new BusinessException("Profile photo is required", HttpStatus.BAD_REQUEST);
		}

		User user = findByEmail(email);
		replaceProfilePhoto(user, profilePhoto);
		return toResponse(userRepository.save(user));
	}

	@Override
	@Transactional(readOnly = true)
	public ProfilePhotoContent loadCurrentUserProfilePhoto(String email) {
		User user = findByEmail(email);
		if (user.getProfilePhotoKey() == null || user.getProfilePhotoKey().isBlank()) {
			throw new ResourceNotFoundException("Profile photo not found");
		}
		return profilePhotoStorageService.loadProfilePhoto(user.getProfilePhotoKey());
	}

	private User findByEmail(String email) {
		return userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private void validateRegistration(UserRegistrationRequest request) {
		String email = request.getEmail().trim().toLowerCase();
		String mobile = request.getMobileNumber().trim();

		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw new BusinessException("Email already exists", HttpStatus.CONFLICT);
		}
		if (userRepository.existsByMobileNumber(mobile)) {
			throw new BusinessException("Mobile number already exists", HttpStatus.CONFLICT);
		}
		if (request.getYearsOfExperience() != null && request.getYearsOfExperience() < 0) {
			throw new BusinessException("Years of experience cannot be negative", HttpStatus.BAD_REQUEST);
		}
	}

	private void replaceProfilePhoto(User user, String rawProfilePhoto) {
		String existingKey = user.getProfilePhotoKey();
		String newKey = profilePhotoStorageService.storeProfilePhoto(rawProfilePhoto, user.getUserId());
		user.setProfilePhotoKey(newKey);

		if (existingKey != null
				&& !existingKey.equals(newKey)
				&& profilePhotoStorageService.isStoredReference(existingKey)) {
			profilePhotoStorageService.deleteProfilePhoto(existingKey);
		}
	}

	private UserProfileResponse toResponse(User user) {
		return new UserProfileResponse(
				user.getUserId(),
				user.getFirstName(),
				user.getLastName(),
				user.getEmail(),
				user.getMobileNumber(),
				profilePhotoStorageService.resolveAccessUrl(user.getProfilePhotoKey()),
				user.getAddress(),
				user.getYearsOfExperience(),
				user.getCurrentSkillLevel(),
				user.getCurrentOrganization(),
				user.getQualification(),
				user.getFatherName());
	}
}
