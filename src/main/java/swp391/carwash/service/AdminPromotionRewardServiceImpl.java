package swp391.carwash.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.request.PromotionReward.PromotionRewardCreateRequest;
import swp391.carwash.dto.request.PromotionReward.PromotionRewardUpdateRequest;
import swp391.carwash.dto.response.Reward.RewardRedemptionResponse;
import swp391.carwash.dto.response.Reward.RewardResponse;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.Reward;
import swp391.carwash.entity.RewardRedemption;
import swp391.carwash.enums.DiscountType;
import swp391.carwash.repository.GarageRepository;
import swp391.carwash.repository.RewardRedemptionRepository;
import swp391.carwash.repository.RewardRepository;
import swp391.carwash.enums.RewardStatus;
import java.math.BigDecimal;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminPromotionRewardServiceImpl
        implements AdminPromotionRewardService {

    private static final Set<RewardStatus> VALID_REWARD_STATUSES = Set.of(
            RewardStatus.ACTIVE,
            RewardStatus.INACTIVE,
            RewardStatus.OUT_OF_STOCK,
            RewardStatus.DELETED
    );

    private static final Set<String> VALID_REDEMPTION_STATUSES = Set.of(
            "PENDING",
            "APPROVED",
            "COMPLETED",
            "REJECTED",
            "CANCELLED"
    );

    private final RewardRepository rewardRepository;
    private final RewardRedemptionRepository redemptionRepository;
    private final GarageRepository garageRepository;

    @Override
    @Transactional
    public RewardResponse create(
            PromotionRewardCreateRequest request
    ) {
        Garage garage = getGarage(request.getGarageId());

        validateRewardData(
                request.getDiscountType(),
                request.getDiscountValue(),
                request.getMaxDiscount(),
                request.getMinOrderValue(),
                request.getValidDays()
        );

        Reward reward = Reward.builder()
                .garage(garage)
                .name(request.getName().trim())
                .description(request.getDescription())
                .pointsRequired(request.getPointsRequired())
                .stock(request.getStock())
                .discountType(request.getDiscountType())
                .discountValue(request.getDiscountValue())
                .maxDiscount(request.getMaxDiscount())
                .minOrderValue(request.getMinOrderValue())
                .validDays(request.getValidDays())
                .status(
                        request.getStock() == 0
                                ? RewardStatus.OUT_OF_STOCK
                                : RewardStatus.ACTIVE
                )
                .build();

        return RewardResponse.from(
                rewardRepository.save(reward)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RewardResponse> getAll(
            Integer garageId,
            String status,
            Pageable pageable
    ) {
        getGarage(garageId);

        Page<Reward> rewards;

        if (status == null || status.isBlank()) {
            rewards = rewardRepository
                    .findByGarageIdAndStatusNot(
                            garageId,
                            RewardStatus.DELETED,
                            pageable
                    );
        } else {
            RewardStatus rewardStatus;

            try {
                rewardStatus = RewardStatus.valueOf(
                        normalize(status)
                );
            } catch (IllegalArgumentException e) {
                throw badRequest("Trạng thái phần thưởng không hợp lệ.");
            }

            validateRewardStatus(rewardStatus);

            rewards = rewardRepository.findByGarageIdAndStatus(
                    garageId,
                    rewardStatus,
                    pageable
            );
        }

        return rewards.map(RewardResponse::from);
    }

    @Override
    @Transactional
    public RewardResponse update(
            Integer rewardId,
            PromotionRewardUpdateRequest request
    ) {
        Reward reward = getRewardForUpdate(rewardId);

        updateBasicFields(reward, request);

        validateRewardData(
                reward.getDiscountType(),
                reward.getDiscountValue(),
                reward.getMaxDiscount(),
                reward.getMinOrderValue(),
                reward.getValidDays()
        );

        updateStatus(reward, request.getStatus());

        return RewardResponse.from(
                rewardRepository.save(reward)
        );
    }

    @Override
    @Transactional
    public void delete(Integer rewardId) {
        Reward reward = rewardRepository.findById(rewardId)
                .orElseThrow(() -> notFound(
                        "Phần thưởng không tồn tại."
                ));

        if (reward.getStatus() == RewardStatus.DELETED) {
            return;
        }

        reward.setStatus(RewardStatus.DELETED);
        rewardRepository.save(reward);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RewardRedemptionResponse> getRedemptions(
            Integer garageId,
            String status,
            Pageable pageable
    ) {
        getGarage(garageId);

        Page<RewardRedemption> result;

        if (status == null || status.isBlank()) {
            result = redemptionRepository
                    .findByGarageIdOrderByRedeemedAtDesc(
                            garageId,
                            pageable
                    );
        } else {
            String normalizedStatus = normalize(status);

            if (!VALID_REDEMPTION_STATUSES.contains(
                    normalizedStatus
            )) {
                throw badRequest(
                        "Trạng thái đổi thưởng không hợp lệ."
                );
            }

            result = redemptionRepository
                    .findByGarageIdAndStatusOrderByRedeemedAtDesc(
                            garageId,
                            normalizedStatus,
                            pageable
                    );
        }

        return result.map(
                RewardRedemptionResponse::fromEntity
        );
    }

    private void updateBasicFields(
            Reward reward,
            PromotionRewardUpdateRequest request
    ) {
        if (request.getName() != null) {
            reward.setName(request.getName().trim());
        }

        if (request.getDescription() != null) {
            reward.setDescription(request.getDescription());
        }

        if (request.getPointsRequired() != null) {
            reward.setPointsRequired(
                    request.getPointsRequired()
            );
        }

        if (request.getStock() != null) {
            reward.setStock(request.getStock());
        }

        if (request.getDiscountType() != null) {
            reward.setDiscountType(
                    request.getDiscountType()
            );
        }

        if (request.getDiscountValue() != null) {
            reward.setDiscountValue(
                    request.getDiscountValue()
            );
        }

        if (request.getMaxDiscount() != null) {
            reward.setMaxDiscount(
                    request.getMaxDiscount()
            );
        }

        if (request.getMinOrderValue() != null) {
            reward.setMinOrderValue(
                    request.getMinOrderValue()
            );
        }

        if (request.getValidDays() != null) {
            reward.setValidDays(
                    request.getValidDays()
            );
        }
    }

    private void updateStatus(
            Reward reward,
            RewardStatus requestedStatus
    ) {
        if (requestedStatus != null) {

            RewardStatus status = requestedStatus;

            validateRewardStatus(status);

            if (status == RewardStatus.DELETED) {
                throw badRequest(
                        "Hãy dùng API DELETE để xóa phần thưởng."
                );
            }

            reward.setStatus(status);
        }

        if (reward.getStock() == 0) {
            reward.setStatus(RewardStatus.OUT_OF_STOCK);
        } else if (RewardStatus.OUT_OF_STOCK.equals(reward.getStatus())) {
            reward.setStatus(RewardStatus.ACTIVE);
        }
    }

    private void validateRewardData(
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minOrderValue,
            Integer validDays
    ) {
        if (discountType == null) {
            throw badRequest("Loại giảm giá không hợp lệ.");
        }

        if (discountValue == null
                || discountValue.compareTo(
                BigDecimal.ZERO
        ) <= 0) {
            throw badRequest(
                    "Giá trị giảm phải lớn hơn 0."
            );
        }

        if (discountType == DiscountType.PERCENTAGE
                && discountValue.compareTo(
                BigDecimal.valueOf(100)
        ) > 0) {
            throw badRequest(
                    "Phần trăm giảm không được vượt quá 100."
            );
        }

        if (maxDiscount != null
                && maxDiscount.compareTo(
                BigDecimal.ZERO
        ) < 0) {
            throw badRequest(
                    "Giảm tối đa không được âm."
            );
        }

        if (minOrderValue == null
                || minOrderValue.compareTo(
                BigDecimal.ZERO
        ) < 0) {
            throw badRequest(
                    "Giá trị đơn tối thiểu không được âm."
            );
        }

        if (validDays == null || validDays <= 0) {
            throw badRequest(
                    "Số ngày hiệu lực phải lớn hơn 0."
            );
        }
    }

    private Garage getGarage(Integer garageId) {
        return garageRepository.findById(garageId)
                .orElseThrow(() -> notFound(
                        "Garage không tồn tại."
                ));
    }

    private Reward getRewardForUpdate(Integer rewardId) {
        Reward reward = rewardRepository.findById(rewardId)
                .orElseThrow(() -> notFound(
                        "Phần thưởng không tồn tại."
                ));

        return reward;
    }

    private void validateRewardStatus(RewardStatus status) {
        if (!VALID_REWARD_STATUSES.contains(status)) {
            throw badRequest(
                    "Trạng thái phần thưởng không hợp lệ."
            );
        }
    }


    private ApiException notFound(String message) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                message
        );
    }

    private ApiException badRequest(String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
    private String normalize(String value) {
        return value == null
                ? null
                : value.trim().toUpperCase();
    }
}