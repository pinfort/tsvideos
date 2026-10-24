INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (1, 'program1', 1, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (2, 'program2', 2, 'ERROR');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (3, 'program3', 3, 'REGISTERED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (4, 'program4', 4, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (5, 'program5', 5, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (6, 'program6', 6, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (7, 'program7', 7, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (8, 'program8', 8, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (9, 'program9', 9, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (10, 'program10', 10, 'COMPLETED');
INSERT INTO `test`.`program`(id, name, executed_file_id, status) VALUES (11, 'program11', 11, 'COMPLETED');

INSERT INTO `test`.`executed_file`(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status) VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');

INSERT INTO `test`.`splitted_file`(id,executed_file_id,file,size,duration,status) VALUES(1,1,'test8',6,7.0,'COMPRESS_SAVED');

INSERT INTO `test`.`created_file`(id,splitted_file_id,file,size,mime,encoding,status) VALUES(1,1,'test',3,'test2','test3','REGISTERED');

-- Chapters probed for created_file 1 (join_logo_scp style titles, one untitled). Rows created by tests keep the
-- default chapters_probed = FALSE, i.e. "not probed"; "probed, no chapters" is chapters_probed = TRUE with no rows.
UPDATE `test`.`created_file` SET chapters_probed = TRUE WHERE id = 1;
INSERT INTO `test`.`created_file_chapter`(created_file_id,chapter_index,start_seconds,end_seconds,title) VALUES(1,0,0.0,5.739,'A');
INSERT INTO `test`.`created_file_chapter`(created_file_id,chapter_index,start_seconds,end_seconds,title) VALUES(1,1,5.739,65.0,'CM');
INSERT INTO `test`.`created_file_chapter`(created_file_id,chapter_index,start_seconds,end_seconds,title) VALUES(1,2,65.0,95.0,NULL);
