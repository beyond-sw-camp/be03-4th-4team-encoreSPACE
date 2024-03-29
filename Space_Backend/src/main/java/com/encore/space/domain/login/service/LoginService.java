package com.encore.space.domain.login.service;

import com.encore.space.common.config.PasswordConfig;
import com.encore.space.common.config.WebConfig;
import com.encore.space.domain.login.domain.GitHubMember;
import com.encore.space.domain.login.domain.GoogleMember;
import com.encore.space.domain.login.domain.MemberInfo;
import com.encore.space.domain.login.dto.LoginReqDto;
import com.encore.space.domain.login.jwt.JwtProvider;
import com.encore.space.domain.member.domain.LoginType;
import com.encore.space.domain.member.domain.Member;
import com.encore.space.domain.member.dto.reqdto.MemberReqDto;
import com.encore.space.domain.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class LoginService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final MemberService memberService;
    private final JwtProvider jwtProvider;

    // 시큐리티에 있는 것을 가져다 쓰면 순환 참조가 걸림 조심.
    private final PasswordConfig passwordConfig;
    private final RedisTemplate<String, String> redisTemplate;
    private final WebConfig webConfig;

    @Autowired
    public LoginService(
            MemberService memberService,
            JwtProvider jwtProvider,
            PasswordConfig passwordConfig,
            RedisTemplate<String, String> redisTemplate,
            WebConfig webConfig
    ) {
        this.memberService = memberService;
        this.jwtProvider = jwtProvider;
        this.passwordConfig = passwordConfig;
        this.redisTemplate = redisTemplate;
        this.webConfig = webConfig;
    }

    /*
    http://localhost:8080/login/oauth2/code/google
    http://localhost:8080/login/oauth2/code/github
    http://localhost:8080/oauth2/authorization/google
    http://localhost:8080/oauth2/authorization/github
     */

    // Oauth 로그인
    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
        OAuth2User oAuth2User = delegate.loadUser(userRequest);
        MemberInfo info = null;

        // google
        if(userRequest.getClientRegistration().getRegistrationId().equals("google")) {
            info = new GoogleMember(oAuth2User.getAttributes());
        }

        // github
        if(userRequest.getClientRegistration().getRegistrationId().equals("github")){
            info = new GitHubMember(oAuth2User.getAttributes(), userRequest.getAccessToken().getTokenValue());
        }

        // 자동 회원가입
        log.info("Name: " + info.getName());
        log.info("Email: " + info.getEmail());
        log.info("Picture: " + info.getPicture());
        log.info("ProviderId: " + info.getProviderId());
        if(info != null && !memberService.existsByEmail(Objects.requireNonNull(info).getEmail())){
            memberService.memberCreate(
                    MemberReqDto.builder()
                            .name(info.getName().equals("null") ? info.getEmail() : info.getName())
                            .email(info.getEmail())
                            .nickname(info.getName().equals("null") ? info.getEmail() : info.getName())
                            .profile(info.getPicture())
                            .loginType(info.getProvider())
                            .build()
            );
        }
        Member member = memberService.findByEmail(Objects.requireNonNull(info).getEmail());

        // LoginSuccessHandler
        Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
        attributes.put("role", member.getRole());
        attributes.put("email", member.getEmail());


        return new DefaultOAuth2User(
                oAuth2User.getAuthorities(),
                attributes,
                userRequest.getClientRegistration().getProviderDetails()
                        .getUserInfoEndpoint().getUserNameAttributeName()
        );
    }

    // 로그인
    public Object login(LoginReqDto loginReqDto, LoginType loginType) {
        Member member = memberService.findByEmail(loginReqDto.getEmail());

        // sns 회원가입한 사람이 일반 로그인 하려고 할때
        if(member.getPassword() == null && loginType.equals(LoginType.EMAIL)){
            throw new UsernameNotFoundException(
                    "해당 이메일은 "+ member.getLoginType().toString() + "로 로그인 한 계정입니다. \n" +
                            "만일 이메일로 로그인 하고 싶으시다면 패스워드를 설정해주세요."
            );
        }

        // 패스워드 일치 여부
        if(!passwordConfig.passwordEncoder().matches(loginReqDto.getPassword(), member.getPassword())){
            throw new UsernameNotFoundException("비밀번호가 틀렸습니다.");
        }

        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        return jwtProvider.exportToken(member.getEmail(), member.getRole().toString(), webConfig.ipCheck(request));
    }

    public void logout(HttpServletRequest request) {
        redisTemplate.delete(jwtProvider.extractAccessToken(request));
    }
}
