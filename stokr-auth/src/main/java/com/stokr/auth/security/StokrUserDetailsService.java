package com.stokr.auth.security;

import com.stokr.auth.repository.AuthUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StokrUserDetailsService implements UserDetailsService {

    private final AuthUserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository
                .findByEmailIgnoreCaseAndDeletedFalse(username)
                .map(StokrUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
