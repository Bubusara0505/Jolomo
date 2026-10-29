-- SP08-01: водитель принимает и отклоняет заявки на посылки, пока его профиль в статусе из этих правил (данные).
INSERT INTO eligibility_rules (action, profile_status) VALUES ('MANAGE_PARCELS', 'APPROVED');
