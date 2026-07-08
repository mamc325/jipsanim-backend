package com.jipsanim.user.service;

import com.jipsanim.common.error.BusinessException;
import com.jipsanim.common.error.ErrorCode;
import com.jipsanim.common.security.JwtTokenProvider;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.dto.LoginRequest;
import com.jipsanim.user.dto.LoginResponse;
import com.jipsanim.user.dto.MeResponse;
import com.jipsanim.user.dto.SignupRequest;
import com.jipsanim.user.dto.SignupResponse;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RealtorRepository realtorRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(UserRepository userRepository, RealtorRepository realtorRepository,
                       PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.realtorRepository = realtorRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        User user = userRepository.save(
                User.create(request.email(), passwordEncoder.encode(request.password()),
                        request.nickname(), request.role()));

        if (request.role() == Role.REALTOR) {
            if (!StringUtils.hasText(request.businessName()) || !StringUtils.hasText(request.phone())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "중개사 가입은 businessName, phone 이 필수입니다.");
            }
            realtorRepository.save(Realtor.create(user, request.businessName(), request.phone()));
        }
        return new SignupResponse(user.getId(), user.getRole());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        String token = tokenProvider.createAccessToken(user.getId(), user.getRole());
        return new LoginResponse(token, user.getRole(), tokenProvider.getValiditySeconds());
    }

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new MeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole());
    }
}
