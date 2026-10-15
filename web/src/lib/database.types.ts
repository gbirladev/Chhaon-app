
export type Json = string | number | boolean | null | { [key: string]: Json | undefined } | Json[]

export type Database = {
  
  "graphql_public": {
          Tables: {
            [_ in never]: never
          }
          Views: {
            [_ in never]: never
          }
          Functions: {
            "graphql":
{ Args: { "extensions"?: Json,"operationName"?: string,"query"?: string,"variables"?: Json }; Returns: Json
                           }
          }
          Enums: {
            [_ in never]: never
          }
          CompositeTypes: {
            [_ in never]: never
          }
        },"public": {
          Tables: {
            "appointments": {
                  Row: {
                    "created_at": string,"created_by": string | null,"datetime": string,"doctor_name": string,"id": string,"location": string | null,"notes": string | null,"recipient_id": string,"remind": boolean,"reminded_day_before_at": string | null,"reminded_morning_at": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"created_by"?: string | null,"datetime": string,"doctor_name": string,"id"?: string,"location"?: string | null,"notes"?: string | null,"recipient_id": string,"remind"?: boolean,"reminded_day_before_at"?: string | null,"reminded_morning_at"?: string | null
                  }
                  Update: {
                    "created_at"?: string,"created_by"?: string | null,"datetime"?: string,"doctor_name"?: string,"id"?: string,"location"?: string | null,"notes"?: string | null,"recipient_id"?: string,"remind"?: boolean,"reminded_day_before_at"?: string | null,"reminded_morning_at"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "appointments_created_by_fkey"
      columns: ["created_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "appointments_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"care_circle_members": {
                  Row: {
                    "circle_id": string,"created_at": string,"role": Database["public"]['Enums']["member_role"],"user_id": string
                  }
                  ComputedFields: never
                  Insert: {
                    "circle_id": string,"created_at"?: string,"role"?: Database["public"]['Enums']["member_role"],"user_id": string
                  }
                  Update: {
                    "circle_id"?: string,"created_at"?: string,"role"?: Database["public"]['Enums']["member_role"],"user_id"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "care_circle_members_circle_id_fkey"
      columns: ["circle_id"]
isOneToOne: false
      referencedRelation: "care_circles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "care_circle_members_user_id_fkey"
      columns: ["user_id"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"care_circles": {
                  Row: {
                    "created_at": string,"created_by": string | null,"id": string
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"created_by"?: string | null,"id"?: string
                  }
                  Update: {
                    "created_at"?: string,"created_by"?: string | null,"id"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "care_circles_created_by_fkey"
      columns: ["created_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"care_recipients": {
                  Row: {
                    "age": number | null,"allergies": (string)[],"blood_group": string | null,"call_name": string | null,"caregiver_attested_at": string | null,"checkin_time": string,"circle_id": string,"city": string | null,"conditions": (string)[],"consent_at": string | null,"consent_requested_at": string | null,"created_at": string,"dob": string | null,"doctor_contacts": NonNullable<Json>,"emergency_contacts": NonNullable<Json>,"id": string,"language": string,"name": string,"opted_out_at": string | null,"relation": string,"timezone": string,"updated_at": string,"whatsapp_phone": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "age"?: number | null,"allergies"?: (string)[],"blood_group"?: string | null,"call_name"?: string | null,"caregiver_attested_at"?: string | null,"checkin_time"?: string,"circle_id": string,"city"?: string | null,"conditions"?: (string)[],"consent_at"?: string | null,"consent_requested_at"?: string | null,"created_at"?: string,"dob"?: string | null,"doctor_contacts"?: NonNullable<Json>,"emergency_contacts"?: NonNullable<Json>,"id"?: string,"language"?: string,"name": string,"opted_out_at"?: string | null,"relation"?: string,"timezone"?: string,"updated_at"?: string,"whatsapp_phone"?: string | null
                  }
                  Update: {
                    "age"?: number | null,"allergies"?: (string)[],"blood_group"?: string | null,"call_name"?: string | null,"caregiver_attested_at"?: string | null,"checkin_time"?: string,"circle_id"?: string,"city"?: string | null,"conditions"?: (string)[],"consent_at"?: string | null,"consent_requested_at"?: string | null,"created_at"?: string,"dob"?: string | null,"doctor_contacts"?: NonNullable<Json>,"emergency_contacts"?: NonNullable<Json>,"id"?: string,"language"?: string,"name"?: string,"opted_out_at"?: string | null,"relation"?: string,"timezone"?: string,"updated_at"?: string,"whatsapp_phone"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "care_recipients_circle_id_fkey"
      columns: ["circle_id"]
isOneToOne: false
      referencedRelation: "care_circles"
      referencedColumns: ["id"]
    }
                  ]
                },"checkins": {
                  Row: {
                    "date": string,"id": string,"recipient_id": string,"responded_at": string | null,"sent_at": string | null,"status": Database["public"]['Enums']["checkin_status"]
                  }
                  ComputedFields: never
                  Insert: {
                    "date": string,"id"?: string,"recipient_id": string,"responded_at"?: string | null,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["checkin_status"]
                  }
                  Update: {
                    "date"?: string,"id"?: string,"recipient_id"?: string,"responded_at"?: string | null,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["checkin_status"]
                  }
                  Relationships: [
                    {
      foreignKeyName: "checkins_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"consent_requests": {
                  Row: {
                    "id": string,"phone": string,"requested_at": string,"requested_by": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "id"?: string,"phone": string,"requested_at"?: string,"requested_by"?: string | null
                  }
                  Update: {
                    "id"?: string,"phone"?: string,"requested_at"?: string,"requested_by"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "consent_requests_requested_by_fkey"
      columns: ["requested_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"document_files": {
                  Row: {
                    "created_at": string,"document_id": string,"id": string,"mime": string,"path": string,"position": number,"size_bytes": number,"thumb_path": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"document_id": string,"id"?: string,"mime": string,"path": string,"position"?: number,"size_bytes": number,"thumb_path"?: string | null
                  }
                  Update: {
                    "created_at"?: string,"document_id"?: string,"id"?: string,"mime"?: string,"path"?: string,"position"?: number,"size_bytes"?: number,"thumb_path"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "document_files_document_id_fkey"
      columns: ["document_id"]
isOneToOne: false
      referencedRelation: "documents"
      referencedColumns: ["id"]
    }
                  ]
                },"documents": {
                  Row: {
                    "category": string,"created_at": string,"doc_date": string | null,"doctor": string | null,"id": string,"notes": string | null,"recipient_id": string,"title": string,"updated_at": string,"uploaded_by": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "category"?: string,"created_at"?: string,"doc_date"?: string | null,"doctor"?: string | null,"id"?: string,"notes"?: string | null,"recipient_id": string,"title": string,"updated_at"?: string,"uploaded_by"?: string | null
                  }
                  Update: {
                    "category"?: string,"created_at"?: string,"doc_date"?: string | null,"doctor"?: string | null,"id"?: string,"notes"?: string | null,"recipient_id"?: string,"title"?: string,"updated_at"?: string,"uploaded_by"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "documents_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "documents_uploaded_by_fkey"
      columns: ["uploaded_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"dose_events": {
                  Row: {
                    "id": string,"responded_at": string | null,"schedule_id": string,"scheduled_at": string,"sent_at": string | null,"status": Database["public"]['Enums']["dose_status"],"whatsapp_message_id": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "id"?: string,"responded_at"?: string | null,"schedule_id": string,"scheduled_at": string,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["dose_status"],"whatsapp_message_id"?: string | null
                  }
                  Update: {
                    "id"?: string,"responded_at"?: string | null,"schedule_id"?: string,"scheduled_at"?: string,"sent_at"?: string | null,"status"?: Database["public"]['Enums']["dose_status"],"whatsapp_message_id"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "dose_events_schedule_id_fkey"
      columns: ["schedule_id"]
isOneToOne: false
      referencedRelation: "medicine_schedules"
      referencedColumns: ["id"]
    }
                  ]
                },"emergency_links": {
                  Row: {
                    "active": boolean,"created_at": string,"created_by": string | null,"id": string,"last_viewed_at": string | null,"recipient_id": string,"revoked_at": string | null,"token": string,"view_count": number
                  }
                  ComputedFields: never
                  Insert: {
                    "active"?: boolean,"created_at"?: string,"created_by"?: string | null,"id"?: string,"last_viewed_at"?: string | null,"recipient_id": string,"revoked_at"?: string | null,"token"?: string,"view_count"?: number
                  }
                  Update: {
                    "active"?: boolean,"created_at"?: string,"created_by"?: string | null,"id"?: string,"last_viewed_at"?: string | null,"recipient_id"?: string,"revoked_at"?: string | null,"token"?: string,"view_count"?: number
                  }
                  Relationships: [
                    {
      foreignKeyName: "emergency_links_created_by_fkey"
      columns: ["created_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "emergency_links_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"escalation_policies": {
                  Row: {
                    "alert_after_unanswered": number,"checkin_alert_min": number,"checkin_nudge_min": number,"dose_alert_min": number,"dose_nudge_min": number,"enabled": boolean,"recipient_id": string,"silent_stop_days": number,"updated_at": string
                  }
                  ComputedFields: never
                  Insert: {
                    "alert_after_unanswered"?: number,"checkin_alert_min"?: number,"checkin_nudge_min"?: number,"dose_alert_min"?: number,"dose_nudge_min"?: number,"enabled"?: boolean,"recipient_id": string,"silent_stop_days"?: number,"updated_at"?: string
                  }
                  Update: {
                    "alert_after_unanswered"?: number,"checkin_alert_min"?: number,"checkin_nudge_min"?: number,"dose_alert_min"?: number,"dose_nudge_min"?: number,"enabled"?: boolean,"recipient_id"?: string,"silent_stop_days"?: number,"updated_at"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "escalation_policies_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: true
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"escalations": {
                  Row: {
                    "alerted_at": string | null,"base_at": string,"created_at": string,"handled_by": string | null,"id": string,"kind": string,"nudged_at": string | null,"recipient_id": string,"resolved_at": string | null,"resolved_by": string | null,"slot_key": string
                  }
                  ComputedFields: never
                  Insert: {
                    "alerted_at"?: string | null,"base_at": string,"created_at"?: string,"handled_by"?: string | null,"id"?: string,"kind": string,"nudged_at"?: string | null,"recipient_id": string,"resolved_at"?: string | null,"resolved_by"?: string | null,"slot_key": string
                  }
                  Update: {
                    "alerted_at"?: string | null,"base_at"?: string,"created_at"?: string,"handled_by"?: string | null,"id"?: string,"kind"?: string,"nudged_at"?: string | null,"recipient_id"?: string,"resolved_at"?: string | null,"resolved_by"?: string | null,"slot_key"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "escalations_handled_by_fkey"
      columns: ["handled_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "escalations_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"family_invites": {
                  Row: {
                    "accepted_at": string | null,"accepted_by": string | null,"circle_id": string,"created_at": string,"created_by": string | null,"expires_at": string,"id": string,"revoked_at": string | null,"token_hash": string
                  }
                  ComputedFields: never
                  Insert: {
                    "accepted_at"?: string | null,"accepted_by"?: string | null,"circle_id": string,"created_at"?: string,"created_by"?: string | null,"expires_at"?: string,"id"?: string,"revoked_at"?: string | null,"token_hash": string
                  }
                  Update: {
                    "accepted_at"?: string | null,"accepted_by"?: string | null,"circle_id"?: string,"created_at"?: string,"created_by"?: string | null,"expires_at"?: string,"id"?: string,"revoked_at"?: string | null,"token_hash"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "family_invites_accepted_by_fkey"
      columns: ["accepted_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "family_invites_circle_id_fkey"
      columns: ["circle_id"]
isOneToOne: false
      referencedRelation: "care_circles"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "family_invites_created_by_fkey"
      columns: ["created_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"medicine_schedules": {
                  Row: {
                    "created_at": string,"days_of_week": (number)[],"end_date": string | null,"id": string,"medicine_id": string,"start_date": string,"time_of_day": string
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"days_of_week"?: (number)[],"end_date"?: string | null,"id"?: string,"medicine_id": string,"start_date"?: string,"time_of_day": string
                  }
                  Update: {
                    "created_at"?: string,"days_of_week"?: (number)[],"end_date"?: string | null,"id"?: string,"medicine_id"?: string,"start_date"?: string,"time_of_day"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "medicine_schedules_medicine_id_fkey"
      columns: ["medicine_id"]
isOneToOne: false
      referencedRelation: "medicines"
      referencedColumns: ["id"]
    }
                  ]
                },"medicines": {
                  Row: {
                    "active": boolean,"created_at": string,"critical": boolean,"dosage": string | null,"id": string,"instructions": string | null,"name": string,"photo_url": string | null,"recipient_id": string,"refill_threshold": number | null,"stock_count": number | null,"updated_at": string
                  }
                  ComputedFields: never
                  Insert: {
                    "active"?: boolean,"created_at"?: string,"critical"?: boolean,"dosage"?: string | null,"id"?: string,"instructions"?: string | null,"name": string,"photo_url"?: string | null,"recipient_id": string,"refill_threshold"?: number | null,"stock_count"?: number | null,"updated_at"?: string
                  }
                  Update: {
                    "active"?: boolean,"created_at"?: string,"critical"?: boolean,"dosage"?: string | null,"id"?: string,"instructions"?: string | null,"name"?: string,"photo_url"?: string | null,"recipient_id"?: string,"refill_threshold"?: number | null,"stock_count"?: number | null,"updated_at"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "medicines_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    }
                  ]
                },"notifications": {
                  Row: {
                    "created_at": string,"id": string,"payload": NonNullable<Json>,"read_at": string | null,"type": string,"user_id": string,"whatsapp_attempts": number,"whatsapp_sent_at": string | null,"whatsapp_skipped_at": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "created_at"?: string,"id"?: string,"payload"?: NonNullable<Json>,"read_at"?: string | null,"type": string,"user_id": string,"whatsapp_attempts"?: number,"whatsapp_sent_at"?: string | null,"whatsapp_skipped_at"?: string | null
                  }
                  Update: {
                    "created_at"?: string,"id"?: string,"payload"?: NonNullable<Json>,"read_at"?: string | null,"type"?: string,"user_id"?: string,"whatsapp_attempts"?: number,"whatsapp_sent_at"?: string | null,"whatsapp_skipped_at"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "notifications_user_id_fkey"
      columns: ["user_id"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"phone_opt_outs": {
                  Row: {
                    "opted_out_at": string,"phone": string
                  }
                  ComputedFields: never
                  Insert: {
                    "opted_out_at"?: string,"phone": string
                  }
                  Update: {
                    "opted_out_at"?: string,"phone"?: string
                  }
                  Relationships: [
                    
                  ]
                },"plans": {
                  Row: {
                    "key": string,"max_documents": number | null,"max_medicines": number | null,"max_members": number | null,"max_parents": number | null,"name": string,"sort_order": number
                  }
                  ComputedFields: never
                  Insert: {
                    "key": string,"max_documents"?: number | null,"max_medicines"?: number | null,"max_members"?: number | null,"max_parents"?: number | null,"name": string,"sort_order"?: number
                  }
                  Update: {
                    "key"?: string,"max_documents"?: number | null,"max_medicines"?: number | null,"max_members"?: number | null,"max_parents"?: number | null,"name"?: string,"sort_order"?: number
                  }
                  Relationships: [
                    
                  ]
                },"profiles": {
                  Row: {
                    "alert_confirmed_at": string | null,"alert_consent_at": string | null,"alert_phone": string | null,"alert_requested_at": string | null,"country": string | null,"created_at": string,"email": string | null,"id": string,"is_admin": boolean,"language": string,"name": string | null,"phone": string | null,"plan": string,"policy_version": string | null,"privacy_accepted_at": string | null,"timezone": string,"today_layout": string | null,"trial_ends_at": string,"updated_at": string
                  }
                  ComputedFields: never
                  Insert: {
                    "alert_confirmed_at"?: string | null,"alert_consent_at"?: string | null,"alert_phone"?: string | null,"alert_requested_at"?: string | null,"country"?: string | null,"created_at"?: string,"email"?: string | null,"id": string,"is_admin"?: boolean,"language"?: string,"name"?: string | null,"phone"?: string | null,"plan"?: string,"policy_version"?: string | null,"privacy_accepted_at"?: string | null,"timezone"?: string,"today_layout"?: string | null,"trial_ends_at"?: string,"updated_at"?: string
                  }
                  Update: {
                    "alert_confirmed_at"?: string | null,"alert_consent_at"?: string | null,"alert_phone"?: string | null,"alert_requested_at"?: string | null,"country"?: string | null,"created_at"?: string,"email"?: string | null,"id"?: string,"is_admin"?: boolean,"language"?: string,"name"?: string | null,"phone"?: string | null,"plan"?: string,"policy_version"?: string | null,"privacy_accepted_at"?: string | null,"timezone"?: string,"today_layout"?: string | null,"trial_ends_at"?: string,"updated_at"?: string
                  }
                  Relationships: [
                    {
      foreignKeyName: "profiles_plan_fkey"
      columns: ["plan"]
isOneToOne: false
      referencedRelation: "plans"
      referencedColumns: ["key"]
    }
                  ]
                },"report_values": {
                  Row: {
                    "flag": Database["public"]['Enums']["value_flag"] | null,"id": string,"reference_range": string | null,"report_id": string,"test_name": string,"unit": string | null,"user_verified": boolean,"value": number
                  }
                  ComputedFields: never
                  Insert: {
                    "flag"?: Database["public"]['Enums']["value_flag"] | null,"id"?: string,"reference_range"?: string | null,"report_id": string,"test_name": string,"unit"?: string | null,"user_verified"?: boolean,"value": number
                  }
                  Update: {
                    "flag"?: Database["public"]['Enums']["value_flag"] | null,"id"?: string,"reference_range"?: string | null,"report_id"?: string,"test_name"?: string,"unit"?: string | null,"user_verified"?: boolean,"value"?: number
                  }
                  Relationships: [
                    {
      foreignKeyName: "report_values_report_id_fkey"
      columns: ["report_id"]
isOneToOne: false
      referencedRelation: "reports"
      referencedColumns: ["id"]
    }
                  ]
                },"reports": {
                  Row: {
                    "ai_status": Database["public"]['Enums']["report_ai_status"],"ai_summary": string | null,"created_at": string,"file_url": string,"id": string,"recipient_id": string,"report_date": string | null,"type": string | null,"uploaded_by": string | null
                  }
                  ComputedFields: never
                  Insert: {
                    "ai_status"?: Database["public"]['Enums']["report_ai_status"],"ai_summary"?: string | null,"created_at"?: string,"file_url": string,"id"?: string,"recipient_id": string,"report_date"?: string | null,"type"?: string | null,"uploaded_by"?: string | null
                  }
                  Update: {
                    "ai_status"?: Database["public"]['Enums']["report_ai_status"],"ai_summary"?: string | null,"created_at"?: string,"file_url"?: string,"id"?: string,"recipient_id"?: string,"report_date"?: string | null,"type"?: string | null,"uploaded_by"?: string | null
                  }
                  Relationships: [
                    {
      foreignKeyName: "reports_recipient_id_fkey"
      columns: ["recipient_id"]
isOneToOne: false
      referencedRelation: "care_recipients"
      referencedColumns: ["id"]
    },{
      foreignKeyName: "reports_uploaded_by_fkey"
      columns: ["uploaded_by"]
isOneToOne: false
      referencedRelation: "profiles"
      referencedColumns: ["id"]
    }
                  ]
                },"whatsapp_events": {
                  Row: {
                    "id": string,"received_at": string
                  }
                  ComputedFields: never
                  Insert: {
                    "id": string,"received_at"?: string
                  }
                  Update: {
                    "id"?: string,"received_at"?: string
                  }
                  Relationships: [
                    
                  ]
                }
          }
          Views: {
            [_ in never]: never
          }
          Functions: {
            "accept_family_invite":
{ Args: { "p_token": string }; Returns: string
                           },
"adjust_stock":
{ Args: { "p_delta": number,"p_medicine": string }; Returns: number
                           },
"admin_stats":
{ Args: { "p_days"?: number }; Returns: Json
                           },
"can_access_document":
{ Args: { "p_document": string }; Returns: boolean
                           },
"can_access_medicine":
{ Args: { "p_medicine": string }; Returns: boolean
                           },
"can_access_recipient":
{ Args: { "p_recipient": string }; Returns: boolean
                           },
"can_access_report":
{ Args: { "p_report": string }; Returns: boolean
                           },
"can_access_schedule":
{ Args: { "p_schedule": string }; Returns: boolean
                           },
"check_medicine_input":
{ Args: { "p_dosage": string,"p_name": string,"p_photo": string,"p_recipient": string,"p_times": (string)[] }; Returns: undefined
                           },
"claim_consent_request":
{ Args: { "p_daily_cap": number,"p_phone": string,"p_user": string,"p_window": string }; Returns: Json
                           },
"confirm_dose_slot":
{ Args: { "p_dose_event": string }; Returns: {
              "dosage": string,"medicine_id": string
            }[]
                           },
"consume_stock":
{ Args: { "p_medicine": string,"p_units"?: number }; Returns: undefined
                           },
"create_care_recipient":
{ Args: { "p": Json }; Returns: string
                           },
"create_family_invite":
{ Args: { "p_circle": string }; Returns: string
                           },
"create_medicine":
{ Args: { "p_dosage": string,"p_end"?: string,"p_instructions": string,"p_name": string,"p_photo_url": string,"p_recipient": string,"p_refill_threshold": number,"p_start"?: string,"p_stock": number,"p_times": (string)[] }; Returns: string
                           },
"enable_emergency_link":
{ Args: { "p_recipient": string }; Returns: {
              "active": boolean,
"created_at": string,
"created_by": string | null,
"id": string,
"last_viewed_at": string | null,
"recipient_id": string,
"revoked_at": string | null,
"token": string,
"view_count": number
            }[]
                          SetofOptions: {
        from: "*"
        to: "emergency_links"
        isOneToOne: false
        isSetofReturn: true
      } },
"erase_caregiver":
{ Args: { "p_user": string }; Returns: undefined
                           },
"generate_dose_events":
{ Args: { "p_grace"?: string,"p_horizon"?: string,"p_now"?: string,"p_recipients"?: (string)[] }; Returns: number
                           },
"handle_escalation":
{ Args: { "p_escalation": string }; Returns: boolean
                           },
"has_control_chars":
{ Args: { "p": string }; Returns: boolean
                           },
"invite_hash":
{ Args: { "p_token": string }; Returns: string
                           },
"is_circle_member":
{ Args: { "p_circle": string }; Returns: boolean
                           },
"is_circle_owner":
{ Args: { "p_circle": string }; Returns: boolean
                           },
"leave_circle":
{ Args: { "p_circle": string }; Returns: undefined
                           },
"paused_medicines":
{ Args: Record<PropertyKey, never>; Returns: {
              "medicine_id": string
            }[]
                           },
"paused_recipients":
{ Args: Record<PropertyKey, never>; Returns: {
              "recipient_id": string
            }[]
                           },
"plan_for_circle":
{ Args: { "p_circle": string }; Returns: {
              "key": string,
"max_documents": number | null,
"max_medicines": number | null,
"max_members": number | null,
"max_parents": number | null,
"name": string,
"sort_order": number
            }
                          SetofOptions: {
        from: "*"
        to: "plans"
        isOneToOne: true
        isSetofReturn: false
      } },
"plan_of_user":
{ Args: { "p_user": string }; Returns: {
              "key": string,
"max_documents": number | null,
"max_medicines": number | null,
"max_members": number | null,
"max_parents": number | null,
"name": string,
"sort_order": number
            }
                          SetofOptions: {
        from: "*"
        to: "plans"
        isOneToOne: true
        isSetofReturn: false
      } },
"plan_overview":
{ Args: Record<PropertyKey, never>; Returns: Json
                           },
"plan_pauses":
{ Args: Record<PropertyKey, never>; Returns: Json
                           },
"preview_family_invite":
{ Args: { "p_token": string }; Returns: Json
                           },
"record_emergency_view":
{ Args: { "p_link": string }; Returns: undefined
                           },
"remove_circle_member":
{ Args: { "p_circle": string,"p_user": string }; Returns: undefined
                           },
"revoke_emergency_link":
{ Args: { "p_recipient": string }; Returns: undefined
                           },
"revoke_family_invite":
{ Args: { "p_invite": string }; Returns: undefined
                           },
"rotate_emergency_link":
{ Args: { "p_recipient": string }; Returns: {
              "active": boolean,
"created_at": string,
"created_by": string | null,
"id": string,
"last_viewed_at": string | null,
"recipient_id": string,
"revoked_at": string | null,
"token": string,
"view_count": number
            }[]
                          SetofOptions: {
        from: "*"
        to: "emergency_links"
        isOneToOne: false
        isSetofReturn: true
      } },
"set_medicine_critical":
{ Args: { "p_critical": boolean,"p_medicine": string }; Returns: undefined
                           },
"sole_member_recipients":
{ Args: { "p_user": string }; Returns: {
              "recipient_id": string
            }[]
                           },
"storage_recipient_id":
{ Args: { "p_name": string }; Returns: string
                           },
"text_array_ok":
{ Args: { "a": (string)[],"p_len": number,"p_max": number }; Returns: boolean
                           },
"update_medicine":
{ Args: { "p_dosage": string,"p_instructions": string,"p_medicine": string,"p_name": string,"p_photo_url": string,"p_refill_threshold": number,"p_times": (string)[] }; Returns: undefined
                           },
"wallet_usage":
{ Args: { "p_recipient": string }; Returns: Json
                           }
          }
          Enums: {
            "checkin_status": "pending"|"sent"|"replied"|"missed","dose_status": "pending"|"sent"|"taken"|"missed"|"skipped"|"taken_late","member_role": "owner"|"member","report_ai_status": "pending"|"processing"|"done"|"failed","value_flag": "normal"|"low"|"high"|"critical"
          }
          CompositeTypes: {
            [_ in never]: never
          }
        }
}

type DatabaseWithoutInternals = Omit<Database, '__InternalSupabase'>

type DefaultSchema = DatabaseWithoutInternals[Extract<keyof Database, "public">]

export type Tables<
  DefaultSchemaTableNameOrOptions extends
    | keyof (DefaultSchema["Tables"] & DefaultSchema["Views"])
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
        DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])
    : never = never
> = DefaultSchemaTableNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
      DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])[TableName] extends {
      Row: infer R
    }
    ? R
    : never
  : DefaultSchemaTableNameOrOptions extends keyof (DefaultSchema["Tables"] & DefaultSchema["Views"])
  ? (DefaultSchema["Tables"] & DefaultSchema["Views"])[DefaultSchemaTableNameOrOptions] extends {
      Row: infer R
    }
    ? R
    : never
  : never

export type TablesInsert<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never
> = DefaultSchemaTableNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Insert: infer I
    }
    ? I
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
  ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
      Insert: infer I
    }
    ? I
    : never
  : never

export type TablesUpdate<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never
> = DefaultSchemaTableNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Update: infer U
    }
    ? U
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
  ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
      Update: infer U
    }
    ? U
    : never
  : never

export type Enums<
  DefaultSchemaEnumNameOrOptions extends
    | keyof DefaultSchema["Enums"]
    | { schema: keyof DatabaseWithoutInternals },
  EnumName extends DefaultSchemaEnumNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"]
    : never = never
> = DefaultSchemaEnumNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"][EnumName]
  : DefaultSchemaEnumNameOrOptions extends keyof DefaultSchema["Enums"]
  ? DefaultSchema["Enums"][DefaultSchemaEnumNameOrOptions]
  : never

export type CompositeTypes<
  PublicCompositeTypeNameOrOptions extends
    | keyof DefaultSchema["CompositeTypes"]
    | { schema: keyof DatabaseWithoutInternals },
  CompositeTypeName extends PublicCompositeTypeNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"]
    : never = never
> = PublicCompositeTypeNameOrOptions extends { schema: keyof DatabaseWithoutInternals }
  ? DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"][CompositeTypeName]
  : PublicCompositeTypeNameOrOptions extends keyof DefaultSchema["CompositeTypes"]
  ? DefaultSchema["CompositeTypes"][PublicCompositeTypeNameOrOptions]
  : never

export const Constants = {
  "graphql_public": {
          Enums: {
            
          }
        },"public": {
          Enums: {
            "checkin_status": ["pending", "sent", "replied", "missed"],"dose_status": ["pending", "sent", "taken", "missed", "skipped", "taken_late"],"member_role": ["owner", "member"],"report_ai_status": ["pending", "processing", "done", "failed"],"value_flag": ["normal", "low", "high", "critical"]
          }
        }
} as const
