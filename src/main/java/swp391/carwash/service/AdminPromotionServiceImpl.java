package swp391.carwash.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.request.promotion.PromotionCreateRequest;
import swp391.carwash.dto.request.promotion.PromotionUpdateRequest;
import swp391.carwash.dto.response.PromotionResponse;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.Promotion;
import swp391.carwash.enums.DiscountType;
import swp391.carwash.enums.PromotionStatus;
import swp391.carwash.repository.GarageRepository;
import swp391.carwash.repository.PromotionRepository;
import swp391.carwash.service.AdminPromotionService;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AdminPromotionServiceImpl implements AdminPromotionService {

    private final PromotionRepository promotionRepository;
    private final GarageRepository garageRepository;

    @Override
    @Transactional
    public PromotionResponse create(PromotionCreateRequest request) {

        Garage garage = garageRepository.findById(request.garageId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Garage không tồn tại."
                ));

        validatePromotionData(
                request.discountType(),
                request.discountValue(),
                request.maxDiscount(),
                request.minOrderValue(),
                request.startDate(),
                request.endDate()
        );

        String promoCode = normalizePromoCode(request.promoCode());

        if (promotionRepository.existsByGarageIdAndPromoCodeIgnoreCase(
                request.garageId(),
                promoCode
        )) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Mã khuyến mãi đã tồn tại trong garage."
            );
        }

        Promotion promotion = Promotion.builder()
                .garageId(garage.getId())
                .promoCode(promoCode)
                .discountType(request.discountType())
                .discountValue(request.discountValue())
                // maxDiscount là cột NULLABLE và null = "không giới hạn mức giảm".
                // KHÔNG normalize null -> 0, vì 0 bị hiểu là "giảm tối đa 0đ" khi áp dụng.
                .maxDiscount(request.maxDiscount())
                .minOrderValue(normalizeMoney(request.minOrderValue()))
                .usageLimit(request.usageLimit())
                .usedCount(0)
                .status(PromotionStatus.ACTIVE)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();

        return PromotionResponse.from(
                promotionRepository.save(promotion)
        );
    }

    @Override
    @Transactional
    public PromotionResponse update(
            Integer promotionId,
            PromotionUpdateRequest request
    ) {

        Promotion promotion = getPromotionEntity(promotionId);

        validatePromotionData(
                request.discountType(),
                request.discountValue(),
                request.maxDiscount(),
                request.minOrderValue(),
                request.startDate(),
                request.endDate()
        );

        validateUsageLimit(
                promotion.getUsedCount(),
                request.usageLimit()
        );

        String promoCode = normalizePromoCode(request.promoCode());

        if (promotionRepository
                .existsByGarageIdAndPromoCodeIgnoreCaseAndPromotionIdNot(
                        promotion.getGarageId(),
                        promoCode,
                        promotionId
                )) {

            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Mã khuyến mãi đã tồn tại trong garage."
            );
        }

        promotion.setPromoCode(promoCode);
        promotion.setDiscountType(request.discountType());
        promotion.setDiscountValue(request.discountValue());
        // Xem ghi chú ở create(): null = không giới hạn, không được coerce về 0.
        promotion.setMaxDiscount(request.maxDiscount());
        promotion.setMinOrderValue(normalizeMoney(request.minOrderValue()));
        promotion.setUsageLimit(request.usageLimit());
        promotion.setStartDate(request.startDate());
        promotion.setEndDate(request.endDate());
        promotion.setStatus(request.status());

        return PromotionResponse.from(
                promotionRepository.save(promotion)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PromotionResponse getById(Integer promotionId) {
        return PromotionResponse.from(
                getPromotionEntity(promotionId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PromotionResponse> getAll(
            Integer garageId,
            Pageable pageable
    ) {

        Page<Promotion> promotions;

        if (garageId != null) {
            promotions = promotionRepository.findByGarageIdAndStatusNot(
                    garageId,
                    PromotionStatus.DELETED,
                    pageable
            );
        } else {
            promotions = promotionRepository.findByStatusNot(
                    PromotionStatus.DELETED,
                    pageable
            );
        }

        return promotions.map(PromotionResponse::from);
    }

    @Override
    @Transactional
    public void delete(Integer promotionId) {

        Promotion promotion = getPromotionEntity(promotionId);

        if (promotion.getUsedCount() != null
                && promotion.getUsedCount() > 0) {

            promotion.setStatus(PromotionStatus.DELETED);
            promotionRepository.save(promotion);
            return;
        }

        /*
         * Vẫn nên soft delete để không phá FK với reward,
         * redemption hoặc promotion_usage.
         */
        promotion.setStatus(PromotionStatus.DELETED);
        promotionRepository.save(promotion);
    }

    private Promotion getPromotionEntity(Integer promotionId) {
        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Khuyến mãi không tồn tại."
                ));

        if (promotion.getStatus() == PromotionStatus.DELETED) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Khuyến mãi không tồn tại."
            );
        }

        return promotion;
    }

    private void validatePromotionData(
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            BigDecimal minOrderValue,
            java.time.OffsetDateTime startDate,
            java.time.OffsetDateTime endDate
    ) {

        if (!endDate.isAfter(startDate)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Ngày kết thúc phải sau ngày bắt đầu."
            );
        }

        if (discountType == DiscountType.PERCENTAGE
                && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Phần trăm giảm giá không được vượt quá 100."
            );
        }

        if (discountType == DiscountType.FIXED_AMOUNT
                && maxDiscount != null
                && maxDiscount.compareTo(BigDecimal.ZERO) > 0) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Giảm giá cố định không cần giá trị giảm tối đa."
            );
        }

        if (minOrderValue != null
                && minOrderValue.compareTo(BigDecimal.ZERO) < 0) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Giá trị đơn tối thiểu không được âm."
            );
        }
    }

    private void validateUsageLimit(
            Integer usedCount,
            Integer usageLimit
    ) {
        if (usageLimit != null
                && usedCount != null
                && usageLimit < usedCount) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Số lượt sử dụng tối đa không được nhỏ hơn số lượt đã dùng."
            );
        }
    }

    private String normalizePromoCode(String promoCode) {
        return promoCode.trim().toUpperCase();
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}