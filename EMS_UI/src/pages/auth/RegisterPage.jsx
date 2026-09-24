// ems_frontend/src/pages/auth/RegisterPage.jsx
import { useNavigate, Link } from 'react-router-dom'
import { useDispatch, useSelector } from 'react-redux'
import { Button, Box, Typography, Alert, CircularProgress, Grid } from '@mui/material'
import { useForm } from 'react-hook-form'
import { yupResolver } from '@hookform/resolvers/yup'
import * as yup from 'yup'
import { registerStart, registerSuccess, registerFailure } from '../../store/slices/authSlice'
import { authAPI } from '../../api/authAPI'
import { getApiErrorMessage } from '../../utils/apiError'
import AuthLayout from '../../components/layout/AuthLayout'
import PcbField from '../../components/common/PcbField'
import { tokens, ctaButton } from '../../styles/tokens'

const schema = yup.object().shape({
  firstName: yup.string().required('First name is required'),
  lastName: yup.string().required('Last name is required'),
  email: yup.string().email('Invalid email').required('Email is required'),
  mobileNumber: yup.string().matches(/^[0-9]{10}$/, 'Mobile number must be 10 digits').required('Mobile number is required'),
  password: yup.string()
    .min(12, 'Password must be at least 12 characters')
    .matches(/[A-Z]/, 'Must contain an uppercase letter')
    .matches(/[a-z]/, 'Must contain a lowercase letter')
    .matches(/[0-9]/, 'Must contain a digit')
    .matches(/[^A-Za-z0-9]/, 'Must contain a special character')
    .required('Password is required'),
  confirmPassword: yup.string()
    .oneOf([yup.ref('password')], 'Passwords must match')
    .required('Confirm password is required'),
  yearsOfExperience: yup.number().typeError('Enter a number').min(0, 'Invalid').required('Experience is required'),
  currentSkillLevel: yup.string().required('Skill level is required'),
  qualification: yup.string().required('Qualification is required'),
  currentOrganization: yup.string().required('Organization is required'),
  fatherName: yup.string().required("Father's name is required"),
  address: yup.string().required('Address is required')
})

const SKILL_LEVELS = [
  { value: '', label: 'Select level' },
  { value: 'L1', label: 'L1 · Foundation' },
  { value: 'L2', label: 'L2 · Intermediate' },
  { value: 'L3', label: 'L3 · Advanced' }
]

const RegisterPage = () => {
  const navigate = useNavigate()
  const dispatch = useDispatch()
  const { isLoading, error } = useSelector((state) => state.auth)
  const { register, handleSubmit, formState: { errors } } = useForm({
    resolver: yupResolver(schema)
  })

  const onSubmit = async (data) => {
    dispatch(registerStart())
    try {
      const response = await authAPI.register({
        firstName: data.firstName,
        lastName: data.lastName,
        email: data.email,
        mobileNumber: data.mobileNumber,
        password: data.password,
        address: data.address,
        yearsOfExperience: Number(data.yearsOfExperience),
        currentSkillLevel: data.currentSkillLevel,
        currentOrganization: data.currentOrganization,
        qualification: data.qualification,
        fatherName: data.fatherName
      })
      dispatch(registerSuccess())
      // The user ID is minted from the name and email server-side, so this is
      // the first and only place the new account hears what it is.
      const userId = response?.data?.data?.user?.userId
      navigate('/login', {
        state: userId
          ? { authNotice: `Account created. Your user ID is ${userId} — sign in with your email.` }
          : null
      })
    } catch (err) {
      dispatch(registerFailure(getApiErrorMessage(err, 'Registration failed. Please try again.')))
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Register to begin your L1 certification journey."
      stamp="EMS-ENROL v2.4"
      wide
    >
      {error && <Alert severity="error" sx={{ mb: 2.5 }}>{error}</Alert>}

      <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
        <Grid container columnSpacing={2}>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="First name" error={errors.firstName?.message} {...register('firstName')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Last name" error={errors.lastName?.message} {...register('lastName')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Email" type="email" placeholder="you@company.com"
              error={errors.email?.message} {...register('email')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Mobile number" inputMode="numeric"
              error={errors.mobileNumber?.message} {...register('mobileNumber')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Password" type="password" autoComplete="new-password"
              error={errors.password?.message} {...register('password')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Confirm password" type="password" autoComplete="new-password"
              error={errors.confirmPassword?.message} {...register('confirmPassword')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Years of experience" type="number"
              error={errors.yearsOfExperience?.message} {...register('yearsOfExperience')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Skill level" options={SKILL_LEVELS} defaultValue=""
              error={errors.currentSkillLevel?.message} {...register('currentSkillLevel')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Qualification" error={errors.qualification?.message} {...register('qualification')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Organization" error={errors.currentOrganization?.message} {...register('currentOrganization')} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <PcbField dense label="Father's name" error={errors.fatherName?.message} {...register('fatherName')} />
          </Grid>
          <Grid item xs={12}>
            <PcbField dense multiline rows={2} label="Address" error={errors.address?.message} {...register('address')} />
          </Grid>
        </Grid>

        <Button fullWidth variant="contained" type="submit" disabled={isLoading} sx={{ ...ctaButton, mt: 1.5 }}>
          {isLoading ? (
            <>
              <CircularProgress size={16} sx={{ color: '#062017', mr: 1.25 }} />
              Enrolling
            </>
          ) : (
            'Create account'
          )}
        </Button>
      </Box>

      <Typography sx={{ textAlign: 'center', fontSize: 14, color: tokens.body, mt: 3 }}>
        Already have an account?{' '}
        <Link to="/login" style={{ fontWeight: 700, color: tokens.copperLt, textDecoration: 'none' }}>
          Sign in
        </Link>
      </Typography>
    </AuthLayout>
  )
}

export default RegisterPage
