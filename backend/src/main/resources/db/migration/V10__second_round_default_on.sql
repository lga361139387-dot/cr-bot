UPDATE app_setting
SET "value" = 'true', updated_at = NOW()
WHERE "key" = 'review.secondRoundEnabled';
