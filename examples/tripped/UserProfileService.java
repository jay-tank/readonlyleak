package com.example.users;

import org.springframework.transaction.annotation.Transactional;

/**
 * Marked readOnly = true because the author only intended this to look up
 * a user and touch a "last seen" timestamp cache -- except the timestamp
 * update below goes through the repository's save(), a real write, inside
 * a transaction the author explicitly told Spring/the JPA provider not to
 * expect writes from.
 */
public class UserProfileService {

    private final UserRepository userRepository;

    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public User touchLastSeen(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setLastSeenAt(java.time.Instant.now());
        userRepository.save(user);
        return user;
    }
}
