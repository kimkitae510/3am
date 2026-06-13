-- MySQL dump 10.13  Distrib 8.0.41, for Win64 (x86_64)
--
-- Host: localhost    Database: threeam
-- ------------------------------------------------------
-- Server version	8.0.41

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `assessment_factors`
--

DROP TABLE IF EXISTS `assessment_factors`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `assessment_factors` (
  `assessment_id` bigint NOT NULL,
  `factor_name` varchar(20) NOT NULL,
  `level` varchar(20) NOT NULL,
  `evidence` text NOT NULL,
  `rationale` varchar(300) DEFAULT NULL,
  `stage` varchar(10) DEFAULT NULL,
  KEY `idx_af_assessment` (`assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `assessment_unanswered`
--

DROP TABLE IF EXISTS `assessment_unanswered`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `assessment_unanswered` (
  `assessment_id` bigint NOT NULL,
  `question` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL,
  KEY `idx_assessment_unanswered` (`assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `assessment_watch`
--

DROP TABLE IF EXISTS `assessment_watch`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `assessment_watch` (
  `assessment_id` bigint NOT NULL,
  `watch_point` varchar(300) NOT NULL,
  `effect` varchar(300) NOT NULL,
  KEY `idx_aw_assessment` (`assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `assessments`
--

DROP TABLE IF EXISTS `assessments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `assessments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `probability` int DEFAULT NULL,
  `breakup_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `breakup_type_secondary` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type_evidence` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `jump_rule` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relapse_risk` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relapse_reason` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `story_id` bigint NOT NULL,
  `verdict` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `chat_direction` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=295 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_fail_streak`
--

DROP TABLE IF EXISTS `chat_fail_streak`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_fail_streak` (
  `user_id` bigint NOT NULL,
  `streak` int NOT NULL,
  `last_failed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `email_verifications`
--

DROP TABLE IF EXISTS `email_verifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `email_verifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(100) NOT NULL,
  `code_hash` varchar(64) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `attempt_count` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_email_verifications_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `entitlements`
--

DROP TABLE IF EXISTS `entitlements`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `entitlements` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `kind` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `total_count` int NOT NULL,
  `used_count` int NOT NULL,
  `payment_id` bigint DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlements_payment_kind` (`payment_id`,`kind`),
  KEY `idx_entitlements_user_kind` (`user_id`,`kind`)
) ENGINE=InnoDB AUTO_INCREMENT=301 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `generation_lock`
--

DROP TABLE IF EXISTS `generation_lock`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `generation_lock` (
  `lock_key` varchar(60) NOT NULL,
  `locked_until` datetime(6) NOT NULL,
  PRIMARY KEY (`lock_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `messages`
--

DROP TABLE IF EXISTS `messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `story_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_messages_story_id` (`story_id`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1793 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `payments`
--

DROP TABLE IF EXISTS `payments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `order_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payment_key` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `item` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `amount` int NOT NULL,
  `method` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `fail_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `vbank_bank` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `vbank_account` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `vbank_due_at` datetime(6) DEFAULT NULL,
  `cancel_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `canceled_amount` int NOT NULL,
  `cancel_attempts` int NOT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `canceled_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payments_order_id` (`order_id`),
  KEY `idx_payments_user_created` (`user_id`,`created_at`),
  KEY `idx_payments_status_updated` (`status`,`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=134 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `refresh_tokens`
--

DROP TABLE IF EXISTS `refresh_tokens`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `expires_at` datetime(6) NOT NULL,
  `token` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_7tdcd6ab5wsgoudnvj7xf1b7l` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=178 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stories`
--

DROP TABLE IF EXISTS `stories`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `last_read_at` datetime(6) DEFAULT NULL,
  `last_insufficient_at` datetime(6) DEFAULT NULL,
  `last_assess_failed_at` datetime(6) DEFAULT NULL,
  `assess_fail_streak` int NOT NULL DEFAULT '0',
  `last_extracted_message_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_stories_user_updated` (`user_id`,`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=417 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `story_facts`
--

DROP TABLE IF EXISTS `story_facts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `story_facts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `story_id` bigint NOT NULL,
  `fact` varchar(200) NOT NULL,
  `source_assessment_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `source` varchar(20) NOT NULL DEFAULT 'EXTRACTED',
  PRIMARY KEY (`id`),
  KEY `idx_story_facts_story` (`story_id`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1054 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `story_match_profile`
--

DROP TABLE IF EXISTS `story_match_profile`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `story_match_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `story_id` bigint NOT NULL,
  `reason` varchar(20) DEFAULT NULL,
  `sub_reasons` varchar(120) DEFAULT NULL,
  `dumper` varchar(10) DEFAULT NULL,
  `fault` varchar(20) DEFAULT NULL,
  `contact_state` varchar(20) DEFAULT NULL,
  `months_since_breakup` int DEFAULT NULL,
  `dating_months` int DEFAULT NULL,
  `age_group` varchar(20) DEFAULT NULL,
  `gender` varchar(10) DEFAULT NULL,
  `repeat_breakup` tinyint(1) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `partner_has_new` bit(1) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_smp_story` (`story_id`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=91 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `story_memories`
--

DROP TABLE IF EXISTS `story_memories`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `story_memories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `story_id` bigint NOT NULL,
  `summary` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_eus1318mdnf57rhpxjklokhxu` (`story_id`)
) ENGINE=InnoDB AUTO_INCREMENT=127 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_consents`
--

DROP TABLE IF EXISTS `user_consents`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_consents` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `consent_type` varchar(30) NOT NULL,
  `doc_version` varchar(20) NOT NULL,
  `order_id` varchar(64) DEFAULT NULL,
  `agreed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_consents_user` (`user_id`,`consent_type`)
) ENGINE=InnoDB AUTO_INCREMENT=131 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `email` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `provider` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EMAIL',
  `provider_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_6dotkott2kjsp8vw4d0m25fb7` (`email`),
  UNIQUE KEY `uk_users_provider_provider_id` (`provider`,`provider_id`)
) ENGINE=InnoDB AUTO_INCREMENT=73 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping routines for database 'threeam'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-03  2:19:16
