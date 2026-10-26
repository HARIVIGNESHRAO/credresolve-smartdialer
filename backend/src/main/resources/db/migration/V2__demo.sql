INSERT INTO campaigns(id,name,mode,provider,max_active,risk_limit) VALUES('00000000-0000-0000-0000-000000000001','Early resolution • demo','PROGRESSIVE','A',50,0.01);
INSERT INTO agents(id,name,campaign_id,state) SELECT md5('agent-'||n)::uuid,'Agent '||lpad(n::text,2,'0'),'00000000-0000-0000-0000-000000000001','AVAILABLE' FROM generate_series(1,50) n;
INSERT INTO borrowers(id,name) SELECT md5('borrower-'||n)::uuid,'Synthetic borrower '||n FROM generate_series(1,1000) n;
INSERT INTO campaign_borrowers(campaign_id,borrower_id) SELECT '00000000-0000-0000-0000-000000000001',id FROM borrowers;
